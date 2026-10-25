package com.livewall.app

import android.graphics.Bitmap
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * OpenGL ES 2 renderer for one wallpaper surface. Photos and videos both end up as a texture here, so the
 * surface only ever has one producer (EGL) and switching between them never needs a new surface.
 * All calls happen on the main thread; each engine has its own context, so every entry point makes it current.
 */
class GlRenderer {

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    private var photoProgram = 0
    private var videoProgram = 0
    private var particleProgram = 0
    private var maxPointSize = 64f

    private var photoTex = 0
    private var videoTex = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var videoSurface: Surface? = null
    @Volatile private var frameAvailable = false

    private val quad = floatBuffer(4 * 4)
    private val particleBuf = floatBuffer(Scene.MAX_PARTICLES * Scene.FLOATS_PER_PARTICLE)
    private val particleArr = FloatArray(Scene.MAX_PARTICLES * Scene.FLOATS_PER_PARTICLE)
    private val texMatrix = FloatArray(16)
    private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    val isReady get() = eglSurface != EGL14.EGL_NO_SURFACE
    val hasVideo get() = surfaceTexture != null

    fun start(surface: Surface): Boolean {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return fail("eglInitialize")

        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE,
        )
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) || count[0] == 0) {
            return fail("eglChooseConfig")
        }
        val config = configs[0]!!
        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
        )
        if (context == EGL14.EGL_NO_CONTEXT) return fail("eglCreateContext")
        eglSurface = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) return fail("eglCreateWindowSurface")
        if (!makeCurrent()) return fail("eglMakeCurrent")

        photoProgram = program(QUAD_VS, PHOTO_FS)
        videoProgram = program(QUAD_VS, VIDEO_FS)
        particleProgram = program(PARTICLE_VS, PARTICLE_FS)
        val range = FloatArray(2)
        GLES20.glGetFloatv(GLES20.GL_ALIASED_POINT_SIZE_RANGE, range, 0)
        if (range[1] > 0f) maxPointSize = range[1]
        return true
    }

    fun stop() {
        if (display == EGL14.EGL_NO_DISPLAY) return
        if (isReady) {
            makeCurrent()
            clearContent()
            GLES20.glDeleteProgram(photoProgram)
            GLES20.glDeleteProgram(videoProgram)
            GLES20.glDeleteProgram(particleProgram)
        }
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
        // No eglTerminate: the display is shared with the other engine (e.g. the system preview) in this process.
        eglSurface = EGL14.EGL_NO_SURFACE
        context = EGL14.EGL_NO_CONTEXT
        display = EGL14.EGL_NO_DISPLAY
    }

    /** Drops whatever is showing (photo texture or video stream). */
    fun clearContent() {
        if (!isReady) return
        makeCurrent()
        if (photoTex != 0) GLES20.glDeleteTextures(1, intArrayOf(photoTex), 0)
        photoTex = 0
        videoSurface?.release()
        videoSurface = null
        surfaceTexture?.release()
        surfaceTexture = null
        if (videoTex != 0) GLES20.glDeleteTextures(1, intArrayOf(videoTex), 0)
        videoTex = 0
        frameAvailable = false
    }

    fun setPhoto(bitmap: Bitmap) {
        clearContent()
        makeCurrent()
        photoTex = newTexture(GLES20.GL_TEXTURE_2D)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
    }

    /** Returns the Surface a MediaPlayer should decode into; frames then show up as a texture here. */
    fun startVideo(): Surface {
        clearContent()
        makeCurrent()
        videoTex = newTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES)
        val st = SurfaceTexture(videoTex)
        st.setOnFrameAvailableListener({ frameAvailable = true }, Handler(Looper.getMainLooper()))
        surfaceTexture = st
        return Surface(st).also { videoSurface = it }
    }

    fun render(w: Int, h: Int, content: RectF, scene: Scene) {
        if (!isReady || !makeCurrent()) return
        GLES20.glViewport(0, 0, w, h)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val st = surfaceTexture
        if (st != null) {
            if (frameAvailable) {
                frameAvailable = false
                st.updateTexImage()
            }
            st.getTransformMatrix(texMatrix)
            drawQuad(videoProgram, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTex, texMatrix, content, w, h, true, scene)
        } else if (photoTex != 0) {
            drawQuad(photoProgram, GLES20.GL_TEXTURE_2D, photoTex, identity, content, w, h, false, scene)
        }

        val n = scene.writeParticles(w, h, particleArr)
        if (n > 0) drawParticles(n, w, h, scene)

        EGL14.eglSwapBuffers(display, eglSurface)
    }

    private fun drawQuad(
        prog: Int, target: Int, tex: Int, matrix: FloatArray, r: RectF, w: Int, h: Int, flipV: Boolean, scene: Scene,
    ) {
        val l = r.left / w * 2f - 1f
        val rr = r.right / w * 2f - 1f
        val t = 1f - r.top / h * 2f
        val b = 1f - r.bottom / h * 2f
        // Bitmap textures have row 0 at the top; SurfaceTexture coordinates start at the bottom.
        val vt = if (flipV) 1f else 0f
        val vb = if (flipV) 0f else 1f
        quad.clear()
        quad.put(floatArrayOf(l, t, 0f, vt, l, b, 0f, vb, rr, t, 1f, vt, rr, b, 1f, vb)).position(0)

        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(prog)
        val aPos = GLES20.glGetAttribLocation(prog, "aPos")
        val aUv = GLES20.glGetAttribLocation(prog, "aUv")
        quad.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPos)
        quad.position(2)
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aUv)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uTex"), 1, false, matrix, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(target, tex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uS"), 0)
        val e = scene.effects
        val f = e.filter
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uSat"), f.saturation)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uContrast"), f.contrast)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uLift"), f.lift)
        GLES20.glUniform3f(GLES20.glGetUniformLocation(prog, "uTint"), f.r, f.g, f.b)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uBright"), e.brightness * scene.fade)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uVignette"), if (e.vignette) 0.75f else 0f)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(prog, "uRes"), w.toFloat(), h.toFloat())
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aUv)
    }

    private fun drawParticles(n: Int, w: Int, h: Int, scene: Scene) {
        // Convert pixel positions to clip space and clamp sizes to what the GPU supports.
        for (i in 0 until n) {
            val o = i * Scene.FLOATS_PER_PARTICLE
            particleArr[o] = particleArr[o] / w * 2f - 1f
            particleArr[o + 1] = 1f - particleArr[o + 1] / h * 2f
            particleArr[o + 2] = particleArr[o + 2].coerceAtMost(maxPointSize)
        }
        particleBuf.clear()
        particleBuf.put(particleArr, 0, n * Scene.FLOATS_PER_PARTICLE).position(0)

        GLES20.glEnable(GLES20.GL_BLEND)
        // Screen blend: light adds to the picture without ever darkening it.
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_COLOR)
        val prog = particleProgram
        GLES20.glUseProgram(prog)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uStyle"), scene.effects.style.ordinal.toFloat())
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uGain"), scene.fade * scene.effects.brightness.coerceAtLeast(0.5f))
        val stride = Scene.FLOATS_PER_PARTICLE * 4
        val aPos = GLES20.glGetAttribLocation(prog, "aPos")
        val aSize = GLES20.glGetAttribLocation(prog, "aSize")
        val aColor = GLES20.glGetAttribLocation(prog, "aColor")
        particleBuf.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, stride, particleBuf)
        particleBuf.position(2)
        GLES20.glVertexAttribPointer(aSize, 1, GLES20.GL_FLOAT, false, stride, particleBuf)
        particleBuf.position(3)
        GLES20.glVertexAttribPointer(aColor, 4, GLES20.GL_FLOAT, false, stride, particleBuf)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glEnableVertexAttribArray(aSize)
        GLES20.glEnableVertexAttribArray(aColor)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, n)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aSize)
        GLES20.glDisableVertexAttribArray(aColor)
    }

    private fun makeCurrent(): Boolean =
        isReady && EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)

    private fun newTexture(target: Int): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(target, ids[0])
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    private fun program(vs: String, fs: String): Int {
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, shader(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(prog, shader(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(prog)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) Log.e(TAG, "link failed: ${GLES20.glGetProgramInfoLog(prog)}")
        return prog
    }

    private fun shader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) Log.e(TAG, "shader failed: ${GLES20.glGetShaderInfoLog(s)}")
        return s
    }

    private fun fail(what: String): Boolean {
        Log.e(TAG, "$what failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
        stop()
        return false
    }

    companion object {
        private const val TAG = "LiveWall"

        private fun floatBuffer(n: Int): FloatBuffer =
            ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

        private const val QUAD_VS = """
            attribute vec2 aPos;
            attribute vec2 aUv;
            uniform mat4 uTex;
            varying vec2 vUv;
            void main() {
                gl_Position = vec4(aPos, 0.0, 1.0);
                vUv = (uTex * vec4(aUv, 0.0, 1.0)).xy;
            }
        """

        // Colour grading shared by photos and videos: saturation, contrast, lift, tint, brightness, vignette.
        private const val GRADE = """
            uniform float uSat;
            uniform float uContrast;
            uniform float uLift;
            uniform vec3 uTint;
            uniform float uBright;
            uniform float uVignette;
            uniform vec2 uRes;
            vec4 grade(vec4 c) {
                float l = dot(c.rgb, vec3(0.299, 0.587, 0.114));
                vec3 col = mix(vec3(l), c.rgb, uSat);
                col = (col - 0.5) * uContrast + 0.5 + uLift;
                col *= uTint;
                vec2 p = gl_FragCoord.xy / uRes - 0.5;
                col *= uBright * clamp(1.0 - uVignette * dot(p, p) * 2.0, 0.0, 1.0);
                return vec4(clamp(col, 0.0, 1.0), 1.0);
            }
        """

        private const val PHOTO_FS = """
            precision mediump float;
            varying vec2 vUv;
            uniform sampler2D uS;
        """ + GRADE + """
            void main() { gl_FragColor = grade(texture2D(uS, vUv)); }
        """

        private const val VIDEO_FS = """#extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vUv;
            uniform samplerExternalOES uS;
        """ + GRADE + """
            void main() { gl_FragColor = grade(texture2D(uS, vUv)); }
        """

        private const val PARTICLE_VS = """
            attribute vec2 aPos;
            attribute float aSize;
            attribute vec4 aColor;
            varying vec4 vColor;
            void main() {
                gl_Position = vec4(aPos, 0.0, 1.0);
                gl_PointSize = aSize;
                vColor = aColor;
            }
        """

        // One shape per ParticleStyle (uStyle is its ordinal); vColor.a is the twinkle intensity.
        private const val PARTICLE_FS = """
            precision mediump float;
            varying vec4 vColor;
            uniform float uStyle;
            uniform float uGain;
            void main() {
                vec2 q = gl_PointCoord - vec2(0.5);
                float d = length(q) * 2.0;
                if (d > 1.0) discard;
                vec3 c;
                if (uStyle < 0.5) {
                    // Sparkle: tinted halo around a white-hot core.
                    c = vColor.rgb * exp(-d * d * 6.0) * 0.45 + vec3(exp(-d * d * 70.0));
                } else if (uStyle < 1.5) {
                    // Snow: soft round flake.
                    c = vColor.rgb * smoothstep(1.0, 0.35, d) * 0.85;
                } else if (uStyle < 2.5) {
                    // Rain: thin vertical streak, brightest at the bottom.
                    float x = abs(q.x) * 2.0;
                    float streak = smoothstep(0.14, 0.0, x) * smoothstep(-0.5, 0.5, q.y);
                    c = vColor.rgb * streak;
                } else if (uStyle < 3.5) {
                    // Firefly: warm glow with a bright centre.
                    c = vColor.rgb * (exp(-d * d * 4.0) * 0.6 + exp(-d * d * 30.0));
                } else if (uStyle < 4.5) {
                    // Bubble: thin ring with a glint.
                    float ring = smoothstep(0.16, 0.0, abs(d - 0.82));
                    float glint = exp(-dot(q + vec2(0.18, 0.18), q + vec2(0.18, 0.18)) * 160.0);
                    c = vColor.rgb * (ring * 0.7 + 0.06) + vec3(glint * 0.9);
                } else {
                    // Star: four-point flare.
                    vec2 a = abs(q) * 2.0;
                    float flare = exp(-a.x * 22.0) * exp(-a.y * 3.0) + exp(-a.y * 22.0) * exp(-a.x * 3.0);
                    c = vColor.rgb * exp(-d * d * 8.0) * 0.35 + vec3(flare * 0.8 + exp(-d * d * 60.0));
                }
                gl_FragColor = vec4(c * vColor.a * uGain, 1.0);
            }
        """
    }
}
