package io.github.hypercar.carui

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import io.github.hypercar.session.HyperCarRepo
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 把一块「2K 渲染」的虚拟屏输出按画布尺寸下采样后喂给编码器的 InputSurface。
 *
 * 为什么不再用系统镜像屏（createMirrorDisplay）：
 * 镜像的前提是宿主屏先有一块输出面，而没有输出面的虚拟屏会被 DisplayManagerService 判成
 * state=OFF、合成器根本不出帧（实测镜像出来是黑屏）；给宿主屏挂 ImageReader 哑面虽然能让屏变 ON，
 * 但每帧占 3 个 GPU 缓冲、还多一块显示和一次跨进程合成，手感明显不如直连。
 *
 * 这里只建一块屏：屏的输出面就是我们自己的 [SurfaceTexture]（所以屏是 state=ON 的真屏），
 * 再由本类在 EGL/GLES 线程上把这张 2K 外部纹理按画布尺寸画进编码器 surface。
 * 缩放、时钟、释放全在应用内完成，等价于「直连 + 缩放」。
 */
internal class SurfaceScaler(
    private val encoderSurface: Surface,
    private val srcWidth: Int,
    private val srcHeight: Int,
    private val dstWidth: Int,
    private val dstHeight: Int,
) {
    private val ready = CountDownLatch(1)
    private val drawing = AtomicBoolean(false)
    private val dirty = AtomicBoolean(false)
    private val released = AtomicBoolean(false)

    @Volatile private var running = true
    @Volatile private var failure: String? = null
    @Volatile private var input: Surface? = null

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    // 以下 GL 对象只在 scaler 线程上使用
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var textureId = 0
    private var textureLoc = 0
    private var posLoc = 0
    private var texLoc = 0
    private var matrixLoc = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var posBuffer: FloatBuffer? = null
    private var texBuffer: FloatBuffer? = null
    private val transform = FloatArray(16)
    private var frameCount = 0

    fun inputSurface(): Surface? = input

    fun error(): String? = failure

    /** 建好 EGL 与输入面后返回 true；失败时 [error] 给出原因。 */
    fun start(): Boolean {
        val ht = HandlerThread("dex-scaler").also { it.start() }
        val h = Handler(ht.looper)
        thread = ht
        handler = h
        h.post { initGl() }
        ready.await(4_000, TimeUnit.MILLISECONDS)
        return input != null
    }

    fun release() {
        if (!released.compareAndSet(false, true)) return
        running = false
        val h = handler
        val ht = thread
        handler = null
        thread = null
        if (h == null || ht == null) {
            finishGl()
            return
        }
        h.post { finishGl() }
        runCatching { ht.quitSafely() }
    }

    private fun initGl() {
        try {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) throw IllegalStateException("没有 EGL display")
            val version = IntArray(2)
            if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
                throw IllegalStateException("eglInitialize 失败")
            }
            val attrs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGLExt.EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            if (!EGL14.eglChooseConfig(eglDisplay, attrs, 0, configs, 0, 1, count, 0) || count[0] == 0) {
                throw IllegalStateException("eglChooseConfig 失败")
            }
            val config = configs[0] ?: throw IllegalStateException("没有匹配的 EGLConfig")
            eglContext = EGL14.eglCreateContext(
                eglDisplay, config, EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
            )
            if (eglContext == EGL14.EGL_NO_CONTEXT) throw IllegalStateException("eglCreateContext 失败")
            eglSurface = EGL14.eglCreateWindowSurface(
                eglDisplay, config, encoderSurface, intArrayOf(EGL14.EGL_NONE), 0,
            )
            if (eglSurface == EGL14.EGL_NO_SURFACE) throw IllegalStateException("eglCreateWindowSurface 失败")
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                throw IllegalStateException("eglMakeCurrent 失败")
            }
            program = buildProgram()
            posLoc = GLES20.glGetAttribLocation(program, "aPos")
            texLoc = GLES20.glGetAttribLocation(program, "aTex")
            matrixLoc = GLES20.glGetUniformLocation(program, "uTransform")
            textureLoc = GLES20.glGetUniformLocation(program, "sTex")
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            textureId = ids[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR,
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR,
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE,
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE,
            )
            GLES20.glViewport(0, 0, dstWidth, dstHeight)
            val st = SurfaceTexture(textureId)
            st.setDefaultBufferSize(srcWidth, srcHeight)
            st.setOnFrameAvailableListener({
                dirty.set(true)
                scheduleDraw()
            }, handler)
            surfaceTexture = st
            input = Surface(st)
            posBuffer = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
            texBuffer = floatBuffer(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
            HyperCarRepo.log("DEX-SCALE: EGL 就绪 ${srcWidth}x$srcHeight → ${dstWidth}x$dstHeight")
        } catch (e: Throwable) {
            failure = e.message ?: e.javaClass.simpleName
            HyperCarRepo.log("DEX-SCALE: 初始化失败 $failure")
        } finally {
            ready.countDown()
        }
    }

    private fun scheduleDraw() {
        if (!running) return
        val h = handler ?: return
        if (!drawing.compareAndSet(false, true)) return
        h.post {
            drawing.set(false)
            if (!running) return@post
            dirty.set(false)
            drawFrame()
            // 绘制期间又来的帧：SurfaceTexture 只保留最新一帧，这里补一次绘制，避免停住不动。
            if (dirty.get()) scheduleDraw()
        }
    }

    private fun drawFrame() {
        val st = surfaceTexture ?: return
        if (eglSurface == EGL14.EGL_NO_SURFACE || program == 0) return
        try {
            st.updateTexImage()
            st.getTransformMatrix(transform)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glUniform1i(textureLoc, 0)
            GLES20.glUniformMatrix4fv(matrixLoc, 1, false, transform, 0)
            posBuffer?.let {
                it.position(0)
                GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, it)
                GLES20.glEnableVertexAttribArray(posLoc)
            }
            texBuffer?.let {
                it.position(0)
                GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, 0, it)
                GLES20.glEnableVertexAttribArray(texLoc)
            }
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            // 编码器靠这个时间戳打 pts，不设的话帧时长会乱。
            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, System.nanoTime())
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
            frameCount++
            if (frameCount == 1) HyperCarRepo.log("DEX-SCALE: 首帧已送出")
        } catch (e: Throwable) {
            if (running) HyperCarRepo.log("DEX-SCALE: 绘制失败 " + (e.message ?: e.javaClass.simpleName))
        }
    }

    private fun finishGl() {
        runCatching { surfaceTexture?.setOnFrameAvailableListener(null) }
        runCatching { surfaceTexture?.release() }
        surfaceTexture = null
        runCatching { input?.release() }
        input = null
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            runCatching { EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT) }
            if (program != 0) runCatching { GLES20.glDeleteProgram(program) }
            if (textureId != 0) runCatching { GLES20.glDeleteTextures(1, intArrayOf(textureId), 0) }
            if (eglSurface != EGL14.EGL_NO_SURFACE) runCatching { EGL14.eglDestroySurface(eglDisplay, eglSurface) }
            if (eglContext != EGL14.EGL_NO_CONTEXT) runCatching { EGL14.eglDestroyContext(eglDisplay, eglContext) }
            runCatching { EGL14.eglReleaseThread() }
        }
        eglSurface = EGL14.EGL_NO_SURFACE
        eglContext = EGL14.EGL_NO_CONTEXT
        eglDisplay = EGL14.EGL_NO_DISPLAY
        program = 0
        textureId = 0
        HyperCarRepo.log("DEX-SCALE: 已释放（共送帧 $frameCount）")
    }

    private fun buildProgram(): Int {
        val vertex = compileShader(
            GLES20.GL_VERTEX_SHADER,
            """
            attribute vec4 aPos;
            attribute vec2 aTex;
            uniform mat4 uTransform;
            varying vec2 vTex;
            void main() {
                gl_Position = aPos;
                vTex = (uTransform * vec4(aTex, 0.0, 1.0)).xy;
            }
            """.trimIndent(),
        )
        val fragment = compileShader(
            GLES20.GL_FRAGMENT_SHADER,
            """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES sTex;
            varying vec2 vTex;
            void main() {
                gl_FragColor = texture2D(sTex, vTex);
            }
            """.trimIndent(),
        )
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vertex)
        GLES20.glAttachShader(p, fragment)
        GLES20.glLinkProgram(p)
        val status = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetProgramInfoLog(p)
            GLES20.glDeleteProgram(p)
            throw IllegalStateException("链接着色器失败: $log")
        }
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        return p
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw IllegalStateException("编译着色器失败: $log")
        }
        return shader
    }

    private fun floatBuffer(values: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(values)
            position(0)
        }
}
