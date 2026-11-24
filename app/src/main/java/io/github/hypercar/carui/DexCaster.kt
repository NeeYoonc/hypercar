package io.github.hypercar.carui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.MotionEvent
import io.github.hypercar.core.RustCoreBridge
import io.github.hypercar.session.HyperCarRepo
import io.github.hypercar.shizuku.ShizukuTools
import io.github.hypercar.settings.SettingsStore
import io.github.hypercar.spike.SpikeCaster
import io.github.hypercar.spike.SpikeDisplay
import io.github.hypercar.spike.SpikeShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * DeX 画面源：经 Shizuku 建一块虚拟屏，把三星 DeX 桌面（SecondaryLauncher）放上去，
 * 屏的输出直接写进 H.264 编码器，与自绘 UI 共用同一条推流链路。
 *
 * 与自绘 UI 的差异：
 *  - 画面由 DeX 自己渲染，本模块不绘制任何 UI；
 *  - 触摸走系统注入（[SpikeShell.touchEvent]），不再依赖 CarHomeActivity 的 View 树。
 */
object DexCaster {

    private const val TAG = "HyperCar-Dex"

    /** DeX 桌面在 1080p 级画布上用 240dpi，排布接近真实外接显示器。 */
    private const val DPI = 240
    private const val COMPONENT =
        "com.sec.android.app.launcher/com.honeyspace.dexservice.SecondaryLauncher"

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var caster: SpikeCaster? = null
    @Volatile private var displayId = -1
    private var width = 1920
    private var height = 1080
    private var cpuLock: PowerManager.WakeLock? = null

    @Volatile
    var running: Boolean = false
        private set

    val currentDisplayId: Int get() = displayId

    suspend fun start(context: Context, current: () -> Boolean = { true }): String {
        if (running) {
            installTouchListener()
            runCatching { RustCoreBridge.nativeSetVideoSize(width, height) }
            return "已经在跑（display=$displayId，${width}x$height）"
        }
        val plan = SettingsStore.encodingPlan.encoderCandidates().firstOrNull()
            ?: return "没有可用的编码方案"
        width = plan.width
        height = plan.height
        val next = SpikeCaster(
            width = width,
            height = height,
            fps = plan.fps,
            bitrate = plan.bitrate,
            tag = TAG,
            preferredCodec = SettingsStore.preferredVideoCodec,
        )
        val encoder = try {
            next.startEncoder()
        } catch (e: Exception) {
            next.stop()
            return "编码器启动失败: ${e.message}"
        }
        val surface = next.encoderSurface ?: run {
            next.stop()
            return "编码器没有 InputSurface"
        }
        caster = next
        val bind = SpikeShell.connect(context)
        if (SpikeShell.userServiceBinder == null || !current()) {
            next.stop()
            caster = null
            return "shell 建屏取消（bind=$bind）"
        }
        // DeX 的多窗口依赖系统自由窗口开关；虚拟屏默认是全屏，不打开这两个开关，应用会铺满整屏。
        runCatching { ShizukuTools.exec("settings put global enable_freeform_support 1", 4_000) }
        runCatching { ShizukuTools.exec("settings put global force_resizable_activities 1", 4_000) }
        val created = SpikeShell.createDisplay(surface, width, height, DPI, 0, "hypercar-dex")
        val id = Regex("id=(\\d+)").find(created)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        if (id <= 0) {
            next.stop()
            caster = null
            return "shell 建屏失败: $created（bind=$bind）"
        }
        displayId = id
        val launch = SpikeDisplay.amStartComponentOnDisplay(id, COMPONENT, clearTask = false)
        if (!current()) {
            stopLocked()
            return "会话已停止，取消 DeX 启动"
        }
        installTouchListener()
        holdCpuAwake(context)
        running = true
        CarAudioCapture.start(context)
        runCatching { RustCoreBridge.nativeSetVideoSize(width, height) }
        scope.launch {
            kotlinx.coroutines.delay(900)
            if (!current()) return@launch
            runCatching { RustCoreBridge.nativeSetVideoSize(width, height) }
        }
        val msg = "启动 ok display=$id | ${width}x${height}@${plan.fps} dpi=$DPI | $encoder | bind=$bind | launch=$launch"
        HyperCarRepo.log("DEX: $msg")
        return msg
    }

    fun requestKeyFrame() {
        main.post { caster?.requestSyncFrame() }
    }

    /** 车机侧按键：直接注入到 DeX 所在的屏。 */
    internal fun onConsoleKey(key: CarConsoleKey) {
        val id = displayId
        if (!running || id <= 0) return
        val result = SpikeShell.consoleKey(id, key.keyCode, key.metaState)
        if (result != "OK") HyperCarRepo.log("DEX: console input $key display=$id $result")
    }

    private fun installTouchListener() {
        RustCoreBridge.setTouchListener(object : RustCoreBridge.TouchListener {
            override fun onTouch(action: Int, x: Int, y: Int) {
                val id = displayId
                if (id <= 0) return
                val a = when (action) {
                    0 -> MotionEvent.ACTION_DOWN
                    1 -> MotionEvent.ACTION_UP
                    2 -> MotionEvent.ACTION_MOVE
                    3 -> MotionEvent.ACTION_CANCEL
                    else -> return
                }
                val now = SystemClock.uptimeMillis()
                if (a == MotionEvent.ACTION_DOWN) downTime = now
                val ev = MotionEvent.obtain(
                    if (a == MotionEvent.ACTION_DOWN) now else downTime,
                    now,
                    a,
                    x.toFloat(),
                    y.toFloat(),
                    0,
                )
                try {
                    SpikeShell.touchEvent(id, ev)
                } finally {
                    ev.recycle()
                }
            }
        })
    }

    @Volatile private var downTime = 0L

    private fun holdCpuAwake(context: Context) {
        runCatching {
            cpuLock = (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hypercar:dex-cpu")
                ?.apply {
                    setReferenceCounted(false)
                    acquire(2 * 60 * 60 * 1000L)
                }
        }
    }

    fun stopAsync() {
        scope.launch { stopLocked() }
    }

    private fun stopLocked() {
        release("audio") { CarAudioCapture.stop() }
        release("touch") { RustCoreBridge.setTouchListener(null) }
        if (displayId > 0) release("display") { SpikeShell.releaseDisplay(displayId) }
        displayId = -1
        release("encoder") { caster?.stop() }
        caster = null
        running = false
        release("wake lock") { cpuLock?.takeIf { it.isHeld }?.release() }
        cpuLock = null
        HyperCarRepo.log("DEX: 投屏已停止（DeX 屏与编码器已释放）")
    }

    private inline fun release(resource: String, block: () -> Unit) {
        runCatching(block).onFailure { HyperCarRepo.log("DEX: 释放 $resource 失败 ${it.message}") }
    }
}
