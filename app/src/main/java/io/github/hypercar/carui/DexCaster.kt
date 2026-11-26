package io.github.hypercar.carui

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.InputDevice
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
    /**
     * 建虚拟屏用的 flag 位（AOSP DisplayManager 的 VIRTUAL_DISPLAY_FLAG_* 常量）。
     * 其余位由 [io.github.hypercar.spike.SpikeShellService.createDisplay] 补齐：
     * PUBLIC | PRESENTATION | OWN_CONTENT_ONLY | SUPPORTS_TOUCH(1<<6) | TRUSTED(1<<10) |
     * OWN_DISPLAY_GROUP(1<<11) | ALWAYS_UNLOCKED(1<<12) | OWN_FOCUS(1<<14)。
     */
    private const val FLAG_ROTATES_WITH_CONTENT = 1 shl 7
    private const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
    private const val FLAG_SYSTEM_DECORATIONS = 1 shl 9
    private const val FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13

    /**
     * scrcpy 5.0.1 在同一台机器（同 uid 2000）实测能跑起真 DeX 的那组 flag，
     * `dumpsys display` 里能看到 FLAG_TRUSTED + FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS +
     * canHostTasks=true，手机主屏不出现预览窗。改这里之前先回看那次取证。
     */
    private const val DEX_DISPLAY_FLAGS =
        FLAG_ROTATES_WITH_CONTENT or FLAG_DESTROY_CONTENT_ON_REMOVAL or
            FLAG_SYSTEM_DECORATIONS or FLAG_TOUCH_FEEDBACK_DISABLED

    private const val COMPONENT =
        "com.sec.android.app.launcher/com.honeyspace.dexservice.SecondaryLauncher"

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 与 CarUiCaster 一致：启动/停止串行化，并用会话代次挡住旧任务清理新会话。 */
    private val lifecycle = SessionResourceLifecycle()

    private var caster: SpikeCaster? = null
    @Volatile private var displayId = -1
    private var width = 1920
    private var height = 1080
    /** DeX 实际渲染尺寸（2K 超采样）：比画布大，镜像时缩回画布再编码，文字更锐利。 */
    private var renderWidth = 1920
    private var renderHeight = 1080
    /** 渲染屏的密度：随超采样倍数放大，保证 dp 布局与 1080p 时一致。 */
    private var renderDpi = DPI
    private var cpuLock: PowerManager.WakeLock? = null

    @Volatile
    var running: Boolean = false
        private set

    val currentDisplayId: Int get() = displayId


    /** 把虚拟屏切换成 freeform，并回报 shell 的真实输出，便于在车机上确认是否生效。 */
    private suspend fun applyFreeform(id: Int): String = runCatching {
        val r = ShizukuTools.exec("cmd window set-display-windowing-mode -d $id 5", 4_000)
        "exit=${r.code} out=${r.out.trim()} err=${r.err.trim()}"
    }.getOrElse { "ERR ${it.javaClass.simpleName}: ${it.message}" }

    suspend fun start(context: Context, current: () -> Boolean = { true }): String {
        if (running) {
            installTouchListener()
            runCatching { RustCoreBridge.nativeSetVideoSize(width, height) }
            return "已经在跑（display=$displayId，${width}x$height）"
        }
        val ticket = lifecycle.ticket()
        return lifecycle.start(ticket, current, ::stopLocked) {
            startLocked(context.applicationContext) { lifecycle.isCurrent(ticket) && current() }
        } ?: "会话已停止，取消 DeX 启动"
    }

    private suspend fun startLocked(context: Context, current: () -> Boolean): String {
        val plan = SettingsStore.encodingPlan.encoderCandidates().firstOrNull()
            ?: return "没有可用的编码方案"
        width = plan.width
        height = plan.height
        // 超采样：DeX 按「设置 → 分辨率与帧率 → DeX 渲染分辨率」的目标宽度渲染，
        // 镜像进画布时降采样，文字/图标边缘会明显更干净。dpi 同比放大，dp 尺寸不变。
        val renderTarget = SettingsStore.dexRenderWidth
        val superScale = if (renderTarget > width) renderTarget.toFloat() / width else 1f
        renderWidth = ((width * superScale).toInt()).let { if (it % 2 == 0) it else it - 1 }
        renderHeight = ((height * superScale).toInt()).let { if (it % 2 == 0) it else it - 1 }
        renderDpi = Math.round(DPI * superScale)
        HyperCarRepo.log("DEX: 渲染 $renderWidth" + "x$renderHeight" + " dpi=$renderDpi → 画布 " + "${width}x$height")
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
        // 三星隐藏开发者开关：让副屏进入真正的桌面模式（应用以自由窗口打开、有壁纸和任务栏）。
        // 改完要重启一次才彻底生效，这里每次会话都写一遍保证它不会丢。
        runCatching {
            ShizukuTools.exec("settings put global enable_desktop_mode_on_secondary_display 1", 4_000)
        }
        runCatching { ShizukuTools.exec("settings put global enable_freeform_support 1", 4_000) }
        runCatching { ShizukuTools.exec("settings put global force_resizable_activities 1", 4_000) }
        // 建屏方式由「设置 → 画面与声音 → DeX 建屏方式」决定（默认自建虚拟屏）：
        //  · virtual（默认）—— 下面这段「单块屏 + SurfaceScaler 下采样」；手机主屏无预览窗，
        //    但 DeX 窗口只有通用 freeform 标题栏（没有左/右半屏入口）。
        //  · overlay —— 先建系统叠加视图（开发者选项里的「模拟辅助显示设备」，type=OVERLAY）+ 镜像屏：
        //    只有叠加视图会被三星 DeX 当成真正的桌面显示器，标题栏是 DeX 原生的那一套
        //    （左上「齿轮 + ⌄」，点开就有左半屏/右半屏），贴边分屏、任务栏也都在。
        //    代价：手机屏左上角会画一块「叠加视图预览窗」，它同时是这块屏的 surface 提供者，
        //    无 root 无法隐藏（OverlayDisplayAdapter.java:580-591 / OverlayDisplayWindow.java 源码）。
        // 为什么自建虚拟屏拿不到原生标题栏：DeX 判据是 DisplayDeviceInfo.FLAG_ALLOWS_CONTENT_MODE_SWITCH，
        // 而 VirtualDisplayAdapter.getDisplayDeviceInfoLocked() 从不设置该位（AOSP android16-release），
        // 所以 TYPE_VIRTUAL 屏上 DeX 只能用通用 freeform 标题栏（右侧「… — ⤢ ✕」、没有左/右半屏）。
        // 2026-11-25 真机 A/B：同样 2K@320，叠加视图（无 flag）有原生标题栏；加 should_show_system_decorations
        // 或 own_content_only 后该位被关掉，标题栏就没了 —— 与尺寸/DPI 无关。
        // 上一次会话如果异常结束，镜像屏会留着（vm 不会随进程回收），这里先兜底释放。
        if (mirrorDisplayId > 0) {
            runCatching { SpikeShell.releaseDisplay(mirrorDisplayId) }
            mirrorDisplayId = -1
        }
        // 上一次会话若走的是叠加视图、这次切回自建虚拟屏，残留的叠加屏会一直挂着预览小窗，先清掉。
        if (SettingsStore.dexWindowRoute != "overlay" && SpikeShell.findOverlayDisplay() > 0) {
            runCatching { releaseOverlayDisplayNow() }
        }
        // 画面必须完整：给「屏比输出面大」的虚拟屏灌小面时合成器不缩放，会被裁成左上角
        // （2026-11-25 实测 2560x1440 屏 + 1920x1080 面：车机只看到左上部分，即「开 2k 显示不全」）。
        // 这里走「单块屏 + 应用内 GPU 下采样」：屏尺寸 = 渲染尺寸（有真实输出面，屏才是 state=ON），
        // 输出面是我们自己的 SurfaceTexture，SurfaceScaler 再把这张 2K 纹理按画布尺寸画进编码器
        // InputSurface。这样既保留直连形态（只有一块屏、手机主屏无预览窗），又拿到 2K 渲染的清晰度；
        // 也不再需要宿主屏 + 镜像屏（镜像屏要求宿主屏有输出面，挂 ImageReader 哑面既费缓冲又拖慢）。
        val superSampled = renderWidth > width || renderHeight > height
        var id = -1
        var created = ""
        val overlayId = if (SettingsStore.dexWindowRoute == "overlay") {
            ensureOverlayDisplay(renderWidth, renderHeight, renderDpi, context.packageName)
        } else {
            -1
        }
        if (overlayId > 0) {
            created = SpikeShell.createMirrorDisplay(surface, width, height, DPI, overlayId)
            val mirrorId = Regex("mirror id=(\\d+)").find(created)?.groupValues?.get(1)?.toIntOrNull() ?: -1
            if (mirrorId > 0) {
                id = overlayId
                mirrorDisplayId = mirrorId
                HyperCarRepo.log(
                    "DEX: 叠加视图 display=$id ${renderWidth}x$renderHeight@$renderDpi → " +
                        "镜像=$mirrorId ${width}x$height@$DPI",
                )
            } else {
                HyperCarRepo.log("DEX: 叠加视图镜像失败（$created），回落自建虚拟屏")
                runCatching { releaseOverlayDisplayNow() }
            }
        }
        if (id <= 0 && superSampled) {
            val scaler = runCatching {
                SurfaceScaler(surface, renderWidth, renderHeight, width, height)
            }.getOrNull()
            if (scaler != null && scaler.start()) {
                val sink = scaler.inputSurface()
                if (sink != null) {
                    created = SpikeShell.createDisplay(
                        sink, renderWidth, renderHeight, renderDpi, DEX_DISPLAY_FLAGS, "hypercar-dex",
                    )
                    id = Regex("id=(\\d+)").find(created)?.groupValues?.get(1)?.toIntOrNull() ?: -1
                }
                if (id > 0) {
                    scalerRef = scaler
                    HyperCarRepo.log(
                        "DEX: 超采样 display=$id ${renderWidth}x$renderHeight@$renderDpi → " +
                            "画布 ${width}x$height（GPU 下采样）",
                    )
                } else {
                    runCatching { scaler.release() }
                    HyperCarRepo.log("DEX: 超采样建屏失败（$created），回落直连画布")
                }
            } else {
                HyperCarRepo.log("DEX: 下采样器启动失败（${scaler?.error()}），回落直连画布")
            }
        }
        if (id <= 0) {
            // 回落路线：屏与 surface 同尺寸，不超采样（触摸缩放也随之变 1:1）。
            renderWidth = width
            renderHeight = height
            renderDpi = DPI
            created = SpikeShell.createDisplay(surface, width, height, DPI, DEX_DISPLAY_FLAGS, "hypercar-dex")
            id = Regex("id=(\\d+)").find(created)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        }
        if (id > 0 && !overlayMode) {
            // 给这块屏「开放系统装饰」：创建时已带 1<<9，这里再补一次运行时开关（失败只记日志）。
            val decors = runCatching { SpikeShell.setSystemDecors(id, true) }.getOrDefault(false)
            HyperCarRepo.log(
                "DEX: 虚拟屏 display=$id ${renderWidth}x$renderHeight@$renderDpi 下采样 系统装饰 -> ok=$decors",
            )
            runCatching { ShizukuTools.exec("wm set-display-engagement-mode -d $id 3", 4_000) }
        }
        if (id <= 0) {
            next.stop()
            caster = null
            return "shell 建屏失败: $created（bind=$bind）"
        }
        displayId = id
        // 虚拟屏默认是 fullscreen 窗口模式，应用会整屏铺满、也没有标题栏；
        // 切成 freeform 后每个应用是带标题栏的浮动窗口（mode_id=5 即 WINDOWING_MODE_FREEFORM）。
        HyperCarRepo.log("DEX: freeform display=$id -> " + applyFreeform(id))
        val launch = SpikeDisplay.amStartComponentOnDisplay(id, COMPONENT, clearTask = false)
        if (!current()) {
            stopLocked()
            return "会话已停止，取消 DeX 启动"
        }
        // 安全网：上一次会话若异常退出，面板电源可能停在 OFF（实测 adb 也改不回来，只能重启手机），
        // 这里每次起投屏前先把面板供电恢复成正常，绝不主动关屏。
        runCatching { SpikeShell.setPhysicalScreenPower(true) }
        installTouchListener()
        installPowerProbe(context)
        holdCpuAwake(context)
        running = true
        // 壁纸已由系统按屏自适应（实测桌面上墙纸正常），不再需要 shell 侧 setDimensionHints。
        CarAudioCapture.start(context)
        runCatching { RustCoreBridge.nativeSetVideoSize(width, height) }
        scope.launch {
            kotlinx.coroutines.delay(900)
            if (!current()) return@launch
            runCatching { RustCoreBridge.nativeSetVideoSize(width, height) }
            HyperCarRepo.log("DEX: freeform retry display=$id -> " + applyFreeform(id))
        }
        val msg = "启动 ok display=$id | ${width}x${height}@${plan.fps} dpi=$DPI | $encoder | bind=$bind | launch=$launch"
        HyperCarRepo.log("DEX: $msg")
        // 取证镜像只在 debug 包里开，正式包不多建一块屏。
        if ((context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            dumpDebugFrames(context, width, height, id)
        }
        return msg
    }

    /**
     * 调试取证：把 DeX 所在的那块屏再镜像一份到 ImageReader，存成 PNG。
     * `screencap -d` 对叠加视图无效，这是唯一能看到「车机到底收到什么画面」的办法。
     */
    private fun dumpDebugFrames(context: Context, width: Int, height: Int, sourceDisplayId: Int) {
        scope.launch {
            val dir = java.io.File(context.getExternalFilesDir(null), "dbg")
            runCatching { dir.mkdirs() }
            runCatching { dir.listFiles()?.forEach { it.delete() } }
            val reader = android.media.ImageReader.newInstance(
                width, height, android.graphics.PixelFormat.RGBA_8888, 3
            )
            val created = runCatching {
                SpikeShell.createMirrorDisplay(reader.surface, width, height, DPI, sourceDisplayId)
            }.getOrNull()
            HyperCarRepo.log("DEX-DBG: 取证镜像 source=$sourceDisplayId -> $created")
            if (created == null) {
                runCatching { reader.close() }
                return@launch
            }
            val mirrorId = Regex("id=(\\d+)").find(created)?.groupValues?.get(1)?.toIntOrNull()
            var saved = 0
            val deadline = System.currentTimeMillis() + 8_000L
            while (saved < 3 && System.currentTimeMillis() < deadline) {
                val image = runCatching { reader.acquireLatestImage() }.getOrNull()
                if (image == null) {
                    kotlinx.coroutines.delay(400)
                    continue
                }
                runCatching {
                    val plane = image.planes[0]
                    val rowPadding = plane.rowStride - plane.pixelStride * width
                    val bmp = android.graphics.Bitmap.createBitmap(
                        width + rowPadding / plane.pixelStride, height,
                        android.graphics.Bitmap.Config.ARGB_8888
                    )
                    bmp.copyPixelsFromBuffer(plane.buffer)
                    val cropped = android.graphics.Bitmap.createBitmap(bmp, 0, 0, width, height)
                    val file = java.io.File(dir, "dex-dbg-$saved.png")
                    java.io.FileOutputStream(file).use {
                        cropped.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    saved++
                    HyperCarRepo.log("DEX-DBG: 帧已存 ${file.absolutePath}")
                }
                runCatching { image.close() }
                kotlinx.coroutines.delay(700)
            }
            runCatching { reader.close() }
            mirrorId?.let { runCatching { SpikeShell.releaseDisplay(it) } }
            HyperCarRepo.log("DEX-DBG: 取证结束 saved=$saved")
        }
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

    /** 触摸注入失败只记前几次，避免刷屏。 */
    private var touchErrLogged = 0
    /** 每次会话前几帧触摸留痕，用来判断车机触摸到底有没有到 Kotlin 侧。 */
    private var touchTraceLogged = 0

    private fun installTouchListener() {
        touchErrLogged = 0
        touchTraceLogged = 0
        touchGrantRejected = 0
        RustCoreBridge.setTouchListener(object : RustCoreBridge.TouchListener {
            override fun onTouchFrame(
                engineGeneration: Long,
                screenGrant: Long,
                action: Int,
                pointers: IntArray,
            ) {
                val id = displayId
                if (id <= 0 || pointers.size < 3) return
                val grantOk = runCatching { RustCoreBridge.nativeTouchGrantCurrent(engineGeneration, screenGrant) }
                    .getOrDefault(false)
                if (touchTraceLogged < 5) {
                    touchTraceLogged++
                    HyperCarRepo.log("DEX-TOUCH: action=$action pointers=${pointers.size / 3} " +
                        "grant=$grantOk display=$id")
                }
                if (!grantOk) {
                    if (touchGrantRejected < 6) {
                        touchGrantRejected++
                        HyperCarRepo.log(
                            "DEX-TOUCH: 授权失效 action=$action display=$id gen=$engineGeneration grant=$screenGrant",
                        )
                    }
                    return
                }
                // Rust 侧给的是编码画布坐标；DeX 按更大分辨率渲染时要按比例放大，否则触摸点会整体偏移。
                val sx = if (width > 0) renderWidth.toFloat() / width else 1f
                val sy = if (height > 0) renderHeight.toFloat() / height else 1f
                val count = pointers.size / 3
                val properties = Array(count) { i ->
                    MotionEvent.PointerProperties().apply {
                        this.id = pointers[i * 3]
                        toolType = MotionEvent.TOOL_TYPE_FINGER
                    }
                }
                val coords = Array(count) { i ->
                    MotionEvent.PointerCoords().apply {
                        x = pointers[i * 3 + 1] * sx
                        y = pointers[i * 3 + 2] * sy
                        pressure = 1f
                        size = 1f
                    }
                }
                val now = SystemClock.uptimeMillis()
                if (action and MotionEvent.ACTION_MASK == MotionEvent.ACTION_DOWN) downTime = now
                val ev = MotionEvent.obtain(
                    if (downTime == 0L) now else downTime,
                    now,
                    action,
                    count,
                    properties,
                    coords,
                    0,
                    0,
                    1f,
                    1f,
                    0,
                    0,
                    InputDevice.SOURCE_TOUCHSCREEN,
                    0,
                )
                try {
                    val r = SpikeShell.touchEvent(id, ev)
                    if (r != "OK" && touchErrLogged < 6) {
                        touchErrLogged++
                        HyperCarRepo.log("DEX: 触摸注入失败 display=$id -> $r")
                    }
                } finally {
                    ev.recycle()
                    if (action and MotionEvent.ACTION_MASK == MotionEvent.ACTION_UP ||
                        action and MotionEvent.ACTION_MASK == MotionEvent.ACTION_CANCEL) {
                        downTime = 0L
                    }
                }
            }
        })
    }

    @Volatile private var downTime = 0L
    /** 镜像我们那块屏的 displayId（不是叠加视图本身）。 */
    @Volatile private var mirrorDisplayId = -1
    /** 本轮是否用了系统叠加视图（只有它上面的 DeX 有原生标题栏/分屏菜单）。 */
    @Volatile private var overlayMode = false
    /** 宿主屏的哑输出面：没有输出面的虚拟屏会被判为 state=OFF，合成器不出帧 → 镜像全黑。 */
    private var scalerRef: SurfaceScaler? = null

    /**
     * 息屏/锁屏诊断：用户反馈「手机锁屏后 DeX 只显示、无法触控」。
     * 记下锁屏那一刻我们这块虚拟屏的 state，以及触摸授权是否还有效，
     * 用来区分「屏被断电冻结」和「注入被系统丢弃」两种原因。
     */
    private var powerProbe: BroadcastReceiver? = null
    private var probeContext: Context? = null
    private var touchGrantRejected = 0

    private fun installPowerProbe(context: Context) {
        uninstallPowerProbe()
        val app = context.applicationContext
        val dm = app.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val kg = app.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val pm = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val id = displayId
                val state = runCatching { dm?.getDisplay(id)?.state }.getOrNull()
                HyperCarRepo.log(
                    "DEX-POWER: ${intent?.action} display=$id state=$state " +
                        "interactive=${pm?.isInteractive} keyguard=${kg?.isKeyguardLocked} " +
                        "locked=${kg?.isDeviceLocked}",
                )
            }
        }
        runCatching {
            app.registerReceiver(
                receiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_USER_PRESENT)
                },
            )
            powerProbe = receiver
            probeContext = app
        }
    }

    private fun uninstallPowerProbe() {
        val r = powerProbe ?: return
        val app = probeContext
        powerProbe = null
        probeContext = null
        if (app != null) runCatching { app.unregisterReceiver(r) }
    }

    /** 让系统建一块叠加视图（模拟辅助显示设备），返回它的 displayId；失败返回 -1。 */
    private suspend fun ensureOverlayDisplay(w: Int, h: Int, dpi: Int, pkg: String): Int {
        // 尺寸/密度必须严格匹配：上一次会话残留的叠加屏会沿用旧分辨率，
        // 所以先清掉再按当前请求重建（否则从 1080p 切 2K 时会静默复用旧屏）。
        if (SpikeShell.findOverlayDisplay() > 0) {
            releaseOverlayDisplayNow()
            kotlinx.coroutines.delay(600)
        }
        val size = "$w" + "x" + "$h"
        // 手机屏上会画一块「叠加视图预览窗」，它同时就是该屏的 surface 提供者，无 root 无法隐藏
        // （OverlayDisplayAdapter/OverlayDisplayWindow 源码）。这里刻意不加 gravity / disable_window_interaction：
        // 让窗口保持可拖动、可双指缩放，位置由用户自己随手摆。
        runCatching { ShizukuTools.exec("settings put global overlay_display_devices \"$size/$dpi\"", 4_000) }
        // 屏一出现就要立刻在它上面造一个空任务（DexWarmupActivity），和 SystemUI 建 Desk 抢时间：
        // Desk 的 activateDesk（setLaunchRoot）只发生在 createDeskRoot 的 onCreated 回调里，
        // 那一刻屏上必须有任务能被 addTaskToDesk，否则 Desk 停在 visible=false sz=0，
        // 标题栏就退回通用 freeform（右侧「… — ⤢ ✕」、没有左/右半屏）。
        // 2026-11-26 实测：300ms 一档的轮询拿到 id 再去起应用就已经错过窗口，这里换成 40ms。
        repeat(200) {
            kotlinx.coroutines.delay(40)
            val id = SpikeShell.findOverlayDisplay()
            if (id > 0) {
                overlayMode = true
                fireDeskWarmup(id, pkg)
                return id
            }
        }
        return -1
    }

    /**
     * 在刚建好的叠加屏上造一个空任务，触发 SystemUI 的 Desk 暖机。
     *
     * 这是「桌面标题栏（齿轮+⌄ → 向左对齐/向右对齐）」能否出现的关键一步：
     * 结果看 SystemUI 的 `ShellDesktopMode` 日志，出现
     * `createDesk obtained deskId=… ` → `activateDesk …` → `addTaskToDesk …` 才算暖起来；
     * 只有 `createDesk obtained`、Desk 停在 `sz=0` 就说明窗口错过了时间窗（标题栏会是通用样式）。
     */
    private suspend fun fireDeskWarmup(displayId: Int, pkg: String) {
        val component = "$pkg" + "/.carui.DexWarmupActivity"
        // 注意：`am start` 本身已带 FLAG_ACTIVITY_NEW_TASK；`--activity-new-task` 在这个 ROM 上不存在
        // （会报 `java.lang.IllegalArgumentException: Unknown option: --activity-new-task`）。
        val r = runCatching {
            ShizukuTools.exec("am start --display $displayId -n $component", 5_000)
        }
        HyperCarRepo.log("DEX-WARMUP: display=$displayId -> " + (r.getOrNull() ?: r.exceptionOrNull()))
    }

    /** 同步清掉叠加屏设置（重建/收尾用，不能走 scope.launch 否则会和后续轮询抢时序）。 */
    private suspend fun releaseOverlayDisplayNow() {
        overlayMode = false
        runCatching {
            ShizukuTools.exec("settings put global overlay_display_devices \"\"", 4_000)
        }
    }

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
        val retired = lifecycle.retire()
        scope.launch { lifecycle.stop(retired, ::stopLocked) }
    }

    /**
     * 只清理属于 DeX 会话的资源。普通自绘模式的断开流程也会转到这里，所以：
     *  - 没起过 DeX（没有任何 DeX 资源）就直接返回，不碰共享的音频采集与触摸回调；
     *  - shell 用户服务是共享的（地图卡片、高德投射也在用），不在这里解绑，
     *    否则会把正在保留的地图屏和导航任务一起销毁。
     */
    private suspend fun stopLocked() {
        if (!DexSessionPolicy.ownsResources(running, displayId, mirrorDisplayId, caster != null, overlayMode)) return
        release("audio") { CarAudioCapture.stop() }
        release("touch") { RustCoreBridge.setTouchListener(null) }
        uninstallPowerProbe()
        releaseScaler()
        if (mirrorDisplayId > 0) {
            release("mirror") { SpikeShell.releaseDisplay(mirrorDisplayId) }
            mirrorDisplayId = -1
        }
        if (displayId > 0 && !overlayMode) release("display") { SpikeShell.releaseDisplay(displayId) }
        if (overlayMode) runCatching { releaseOverlayDisplayNow() }
        displayId = -1
        // 安全网：面板电源绝不能停在被我们关掉的状态（实测只能重启手机恢复），收尾时无条件恢复。
        release("panel") { SpikeShell.setPhysicalScreenPower(true) }
        release("encoder") { caster?.stop() }
        caster = null
        running = false
        release("wake lock") { cpuLock?.takeIf { it.isHeld }?.release() }
        cpuLock = null
        HyperCarRepo.log("DEX: 投屏已停止（DeX 屏与编码器已释放，共享 shell 服务保留）")
    }

    private fun releaseScaler() {
        scalerRef?.let { runCatching { it.release() } }
        scalerRef = null
    }

    private inline fun release(resource: String, block: () -> Unit) {
        runCatching(block).onFailure { HyperCarRepo.log("DEX: 释放 $resource 失败 ${it.message}") }
    }
}
