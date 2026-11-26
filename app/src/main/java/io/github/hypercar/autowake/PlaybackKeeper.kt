package io.github.hypercar.autowake

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.util.Log

/**
 * 会话期间让系统把本进程视作「正在播放媒体」。
 *
 * 为什么需要（2026-11-25 实测）：锁屏瞬间三星的 Freecess（应用冻结）会把整个进程挂起，
 * 实测 2.6 秒——音频心跳、视频、网络三条线程同时静默，音频抖动缓冲（465ms）被抽干，
 * 解锁/恢复后又是一批过期帧被丢弃，听感就是「锁屏、解锁各卡一下」；同一次挂起还让
 * RTSP 保活漏拍 1.8 秒（`keepalive ... max_interval_ms=2776 gap=true`），会话随后被车机断开。
 *
 * deviceidle 白名单挡不住 Freecess（我们早在白名单里，仍被冻结）。日志里其它前台音乐 App
 * 是被 `FreecessHandler: skipping freeze ... result : 7` 跳过的——判据是「正在放音频」。
 * 所以这里在会话期间挂一条**静音**音轨（音量 0，用户听不到）+ 一个 STATE_PLAYING 的
 * MediaSession，让 AudioFlinger 侧存在活跃音轨；会话结束（releaseLocks）时立刻停掉。
 *
 * 注意：不请求音频焦点，不抢 Duoko/车机音频，只是让进程保持「播放中」语义。
 */
internal object PlaybackKeeper {
    private const val TAG = "HyperCar-Keeper"
    private const val SAMPLE_RATE = 48_000

    @Volatile private var track: AudioTrack? = null
    @Volatile private var session: MediaSession? = null
    @Volatile private var writer: Thread? = null
    @Volatile private var running = false

    fun start(context: Context) {
        if (running) return
        val app = context.applicationContext
        if (!runCatching { startSession(app) }.getOrDefault(false)) return
        if (!runCatching { startTrack() }.getOrDefault(false)) {
            stop()
            return
        }
        running = true
        Log.i(TAG, "静音保活已启动：系统将视作正在播放媒体，避免锁屏被应用冻结")
    }

    fun stop() {
        if (!running && track == null && session == null) return
        running = false
        writer?.let { runCatching { it.join(500) } }
        writer = null
        track?.let { t ->
            runCatching { if (t.playState == AudioTrack.PLAYSTATE_PLAYING) t.stop() }
            runCatching { t.release() }
        }
        track = null
        session?.let { s ->
            runCatching { s.setActive(false) }
            runCatching { s.release() }
        }
        session = null
        Log.i(TAG, "静音保活已停止")
    }

    private fun startSession(context: Context): Boolean {
        val s = MediaSession(context, "HyperCar-Keeper").apply {
            // 不接管媒体键：否则按耳机/车机的播放暂停会打到这个空会话，抢走用户正在听的 App。
            setFlags(0)
            setPlaybackState(
                PlaybackState.Builder()
                    .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f)
                    .build(),
            )
            setActive(true)
        }
        session = s
        return s.isActive
    }

    private fun startTrack(): Boolean {
        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            Log.w(TAG, "AudioTrack 缓冲区不可用 minBuffer=$minBuffer")
            return false
        }
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    // 必须用 UNKNOWN：CONTENT_TYPE_MUSIC 会被车机首页的音乐卡片当成「本机音乐播放器」，
                    // 把自己的静音会话显示成正在播放的歌（见 MusicBridge.isLocalMusic）。
                    .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuffer, SAMPLE_RATE / 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (t.state != AudioTrack.STATE_INITIALIZED) {
            Log.w(TAG, "AudioTrack 初始化失败 state=${t.state}")
            runCatching { t.release() }
            return false
        }
        t.setVolume(0f)
        t.play()
        track = t
        val silence = ShortArray(SAMPLE_RATE / 10)
        writer = Thread({
            while (running) {
                val n = runCatching { t.write(silence, 0, silence.size) }.getOrDefault(-1)
                if (n < 0) break
            }
        }, "hypercar-keeper-writer").apply {
            isDaemon = true
            start()
        }
        return true
    }
}
