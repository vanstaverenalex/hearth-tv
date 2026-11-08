package com.vanstaveren.hearthtv

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.WindowManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * The live background, drawn with OpenGL so the home screen's glass panes are real frosted glass:
 * the moving scene shows through each pane deeply blurred, gently toned so text stays readable,
 * with a soft sheen in the top-left, a hairline border that catches the light at the top-left and
 * bottom-right, and a soft shadow underneath. Video and glass are drawn at the panel's own resolution (4K).
 *
 * If the glass shader can't be built on a TV, the video still plays (plain pass-through) and
 * [onGlassFailed] lets the UI fall back to drawn glass.
 */
class GlassScene(context: Context) : GLSurfaceView(context), GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    /** One pane of glass, in this view's pixels. */
    data class Panel(
        val left: Float, val top: Float, val right: Float, val bottom: Float,
        val radius: Float, val alpha: Float,
        /** 0 = crystal clear, 1 = fully frosted. */
        val frost: Float,
        /** Shadow strength under the pane (0 = none). */
        val shadow: Float,
    )

    /** Main thread: the video surface for MediaPlayer is ready (or gone, when null). */
    var onSurface: ((Surface?) -> Unit)? = null
    /** Main thread: the glass shader isn't available on this TV. */
    var onGlassFailed: (() -> Unit)? = null

    @Volatile var glassOk = true
        private set
    @Volatile var dark = true
        set(v) { if (field != v) { field = v; requestRender() } }
    @Volatile private var panels: List<Panel> = emptyList()
    @Volatile private var videoW = 16
    @Volatile private var videoH = 9

    private var st: SurfaceTexture? = null
    private var surface: Surface? = null
    private var oesTex = 0
    private val stMat = FloatArray(16)
    private var bufW = 1; private var bufH = 1
    private val density = context.resources.displayMetrics.density

    private var progPlain = 0; private var progDown = 0; private var progBlur = 0; private var progGlass = 0
    private val fbo = IntArray(3); private val fboTex = IntArray(3)
    private val fboW = intArrayOf(960, 480, 480); private val fboH = intArrayOf(540, 270, 270)
    private lateinit var quad: FloatBuffer

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        setRenderer(this)
        renderMode = RENDERMODE_WHEN_DIRTY
        // Draw at the panel's real resolution (the app's UI is 1080p; the TV panel is 4K).
        try {
            @Suppress("DEPRECATION")
            val mode = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.mode
            val w = minOf(mode.physicalWidth, 3840); val h = minOf(mode.physicalHeight, 2160)
            if (w > 1920 && h > 1080) holder.setFixedSize(w, h)
        } catch (_: Exception) {}
    }

    fun setPanels(list: List<Panel>) { if (list != panels) { panels = list; requestRender() } }
    fun setVideoSize(w: Int, h: Int) { if (w > 0 && h > 0) { videoW = w; videoH = h; requestRender() } }

    /** Call from the activity's onPause/onResume (GLSurfaceView needs them). */
    fun hostPause() {
        onPause()
        val s = surface; val t = st
        surface = null; st = null
        post { onSurface?.invoke(null) }
        try { s?.release() } catch (_: Exception) {}
        try { t?.release() } catch (_: Exception) {}
    }
    fun hostResume() = onResume()

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        super.surfaceDestroyed(holder)
        val s = surface; val t = st
        surface = null; st = null
        post { onSurface?.invoke(null) }
        try { s?.release() } catch (_: Exception) {}
        try { t?.release() } catch (_: Exception) {}
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) = requestRender()

    // ---------------------------------------------------------------- GL thread

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val t = IntArray(1)
        GLES20.glGenTextures(1, t, 0)
        oesTex = t[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val texture = SurfaceTexture(oesTex)
        texture.setOnFrameAvailableListener(this)
        android.opengl.Matrix.setIdentityM(stMat, 0)
        st = texture
        val s = Surface(texture)
        surface = s
        post { onSurface?.invoke(s) }

        quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .apply { put(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)); position(0) }

        progPlain = program(VERT, FRAG_PLAIN)
        progDown = program(VERT, FRAG_DOWN)
        progBlur = program(VERT, FRAG_BLUR)
        progGlass = program(VERT, FRAG_GLASS)
        if (progDown == 0 || progBlur == 0 || progGlass == 0) {
            if (glassOk) { glassOk = false; post { onGlassFailed?.invoke() } }
        } else {
            GLES20.glGenFramebuffers(3, fbo, 0)
            GLES20.glGenTextures(3, fboTex, 0)
            for (i in 0..2) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex[i])
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, fboW[i], fboH[i], 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[i])
                GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTex[i], 0)
                if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE && glassOk) {
                    glassOk = false; post { onGlassFailed?.invoke() }
                }
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        bufW = width; bufH = height
        Log.i(TAG, "glass surface ${width}x$height, glass=${glassOk}")
    }

    private var lastTs = 0L
    private var frames = 0
    private var late = 0
    private var downgraded = false

    override fun onDrawFrame(gl: GL10?) {
        val texture = st ?: return
        try { texture.updateTexImage(); texture.getTransformMatrix(stMat) } catch (_: Exception) {}
        watchFrameRate(texture.timestamp)

        // Cover-crop the video to the screen.
        val va = videoW.toFloat() / videoH; val sa = bufW.toFloat() / bufH
        val cx = if (va > sa) sa / va else 1f
        val cy = if (va > sa) 1f else va / sa

        val ps = panels.filter { it.alpha > 0.004f }
        if (!glassOk || ps.isEmpty()) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, bufW, bufH)
            use(progPlain); bindVideo(progPlain, cx, cy)
            draw(progPlain)
            return
        }

        // 1) video → 960×540, 2) blur at 480×270 (three rounds), all in screen space.
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0]); GLES20.glViewport(0, 0, fboW[0], fboH[0])
        use(progDown); bindVideo(progDown, cx, cy)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(progDown, "uStep"), 0.5f / fboW[0], 0.5f / fboH[0])
        draw(progDown)
        blur(fboTex[0], fbo[1], 2f / fboW[0], 0f)
        blur(fboTex[1], fbo[2], 0f, 1f / fboH[1])
        blur(fboTex[2], fbo[1], 1.6f / fboW[1], 0f)
        blur(fboTex[1], fbo[2], 0f, 1.6f / fboH[1])
        blur(fboTex[2], fbo[1], 2.4f / fboW[1], 0f)        // a third round: a deep, creamy frost
        blur(fboTex[1], fbo[2], 0f, 2.4f / fboH[1])

        // 3) the screen: video, with each pane of glass on top
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, bufW, bufH)
        use(progGlass); bindVideo(progGlass, cx, cy)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex[2])
        GLES20.glUniform1i(GLES20.glGetUniformLocation(progGlass, "uBlur"), 1)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(progGlass, "uRes"), bufW.toFloat(), bufH.toFloat())
        val sx = bufW.toFloat() / maxOf(1, width); val sy = bufH.toFloat() / maxOf(1, height)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(progGlass, "uPx"), density * sx)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(progGlass, "uDark"), if (dark) 1f else 0f)
        val rects = FloatArray(4 * MAX); val params = FloatArray(4 * MAX)
        for ((i, p) in ps.take(MAX).withIndex()) {
            // view pixels (y down) → buffer pixels (y up), as centre + half size
            val l = p.left * sx; val r = p.right * sx
            val t = bufH - p.top * sy; val b = bufH - p.bottom * sy
            rects[i * 4] = (l + r) / 2; rects[i * 4 + 1] = (t + b) / 2
            rects[i * 4 + 2] = (r - l) / 2; rects[i * 4 + 3] = (t - b) / 2
            params[i * 4] = p.radius * sx; params[i * 4 + 1] = p.alpha
            params[i * 4 + 2] = p.frost; params[i * 4 + 3] = p.shadow
        }
        GLES20.glUniform4fv(GLES20.glGetUniformLocation(progGlass, "uRect"), MAX, rects, 0)
        GLES20.glUniform4fv(GLES20.glGetUniformLocation(progGlass, "uP"), MAX, params, 0)
        draw(progGlass)
    }

    /**
     * If the TV can't keep up at 4K (video frames being skipped), drop the glass layer to 1080p.
     * The UI is 1080p anyway; this only gives up a little background sharpness.
     */
    private fun watchFrameRate(ts: Long) {
        if (downgraded || bufW <= 1920 || ts == 0L) return
        if (lastTs != 0L && ts > lastTs) {
            frames++
            if (ts - lastTs > 55_000_000L) late++
            if (frames >= 150) {
                if (late * 4 > frames) {
                    downgraded = true
                    Log.w(TAG, "glass: $late of $frames frames late at ${bufW}x$bufH; switching to 1920x1080")
                    post { holder.setFixedSize(1920, 1080); Diag.log(context, "glass: dropped to 1080p ($late/$frames late)") }
                }
                frames = 0; late = 0
            }
        }
        lastTs = ts
    }

    private fun blur(src: Int, dst: Int, dx: Float, dy: Float) {
        val i = fbo.indexOf(dst)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, dst); GLES20.glViewport(0, 0, fboW[i], fboH[i])
        use(progBlur)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, src)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(progBlur, "uTex"), 0)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(progBlur, "uDir"), dx, dy)
        draw(progBlur)
    }

    private fun use(p: Int) = GLES20.glUseProgram(p)

    private fun bindVideo(p: Int, cx: Float, cy: Float) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(p, "uVideo"), 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(p, "uST"), 1, false, stMat, 0)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(p, "uCrop"), cx, cy)
    }

    private fun draw(p: Int) {
        val a = GLES20.glGetAttribLocation(p, "aPos")
        GLES20.glEnableVertexAttribArray(a)
        quad.position(0)
        GLES20.glVertexAttribPointer(a, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(a)
    }

    private fun program(vs: String, fs: String): Int {
        fun shader(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src); GLES20.glCompileShader(s)
            val ok = IntArray(1); GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) { Log.e(TAG, "shader: " + GLES20.glGetShaderInfoLog(s)); GLES20.glDeleteShader(s); return 0 }
            return s
        }
        val v = shader(GLES20.GL_VERTEX_SHADER, vs); val f = shader(GLES20.GL_FRAGMENT_SHADER, fs)
        if (v == 0 || f == 0) return 0
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v); GLES20.glAttachShader(p, f); GLES20.glLinkProgram(p)
        val ok = IntArray(1); GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) { Log.e(TAG, "link: " + GLES20.glGetProgramInfoLog(p)); return 0 }
        return p
    }

    companion object {
        private const val TAG = "HearthGlass"
        const val MAX = 6

        private val VERT = """
attribute vec2 aPos;
varying vec2 vUv;
void main() { vUv = aPos * 0.5 + 0.5; gl_Position = vec4(aPos, 0.0, 1.0); }
"""
        private val VIDEO = """
uniform samplerExternalOES uVideo;
uniform mat4 uST;
uniform vec2 uCrop;
vec3 video(vec2 uv) {
  vec2 c = clamp((uv - 0.5) * uCrop + 0.5, 0.0, 1.0);
  return texture2D(uVideo, (uST * vec4(c, 0.0, 1.0)).xy).rgb;
}
"""
        private val FRAG_PLAIN = """
#extension GL_OES_EGL_image_external : require
precision mediump float;
varying vec2 vUv;
$VIDEO
void main() { gl_FragColor = vec4(video(vUv), 1.0); }
"""
        private val FRAG_DOWN = """
#extension GL_OES_EGL_image_external : require
precision mediump float;
varying vec2 vUv;
uniform vec2 uStep;
$VIDEO
void main() {
  vec3 c = video(vUv + uStep * vec2(-1.0, -1.0)) + video(vUv + uStep * vec2(1.0, -1.0))
         + video(vUv + uStep * vec2(-1.0, 1.0)) + video(vUv + uStep * vec2(1.0, 1.0));
  gl_FragColor = vec4(c * 0.25, 1.0);
}
"""
        private val FRAG_BLUR = """
precision mediump float;
varying vec2 vUv;
uniform sampler2D uTex;
uniform vec2 uDir;
void main() {
  vec3 c = texture2D(uTex, vUv).rgb * 0.2270270;
  c += (texture2D(uTex, vUv + uDir * 1.3846154).rgb + texture2D(uTex, vUv - uDir * 1.3846154).rgb) * 0.3162162;
  c += (texture2D(uTex, vUv + uDir * 3.2307692).rgb + texture2D(uTex, vUv - uDir * 3.2307692).rgb) * 0.0702703;
  gl_FragColor = vec4(c, 1.0);
}
"""
        private val FRAG_GLASS = """
#extension GL_OES_EGL_image_external : require
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying vec2 vUv;
uniform sampler2D uBlur;
uniform vec2 uRes;
uniform float uPx;
uniform float uDark;
uniform vec4 uRect[6];
uniform vec4 uP[6];
$VIDEO

float sdRR(vec2 p, vec2 c, vec2 h, float r) {
  vec2 q = abs(p - c) - h + r;
  return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

void main() {
  vec2 p = vUv * uRes;
  vec3 col = video(vUv);
  for (int i = 0; i < 6; i++) {
    vec4 P = uP[i];
    if (P.y < 0.004) continue;
    vec4 R = uRect[i];
    float d = sdRR(p, R.xy, R.zw, P.x);

    // Soft shadow beneath the pane (outside it only).
    if (P.w > 0.0 && d > 0.0) {
      float ds = sdRR(p, R.xy - vec2(0.0, 6.0 * uPx), R.zw - vec2(2.0 * uPx), P.x);
      float sh = 1.0 - clamp((ds + 6.0 * uPx) / (30.0 * uPx), 0.0, 1.0);
      col *= 1.0 - sh * sh * P.w * P.y;
    }
    if (d > 1.5) continue;

    // Frosted glass: the scene behind, heavily blurred, gently toned for legibility.
    vec3 g = texture2D(uBlur, vUv).rgb;
    float lum = dot(g, vec3(0.299, 0.587, 0.114));
    g = mix(vec3(lum), g, 1.15);
    lum = dot(g, vec3(0.299, 0.587, 0.114));
    // position inside the pane: u left→right, v top→bottom
    float u = (p.x - (R.x - R.z)) / (2.0 * R.z);
    float v = ((R.y + R.w) - p.y) / (2.0 * R.w);
    float sheen = pow(clamp(1.0 - (u * 0.6 + v), 0.0, 1.0), 2.0);
    float k = clamp((u + v) * 0.5, 0.0, 1.0);
    vec3 b;   // border strength: top-left, middle, bottom-right
    if (uDark > 0.5) {
      g *= 0.80;
      float l2 = lum * 0.80 + 0.001;
      g *= (0.58 * (1.0 - exp(-l2 / 0.58))) / l2;   // very bright scenes roll off so white text reads
      g += vec3(0.06) + sheen * 0.10;
      b = vec3(0.55, 0.12, 0.30);
    } else {
      g = g * 0.84 + vec3(0.16);
      float l2 = lum * 0.84 + 0.16;
      g += vec3(0.8 * max(0.0, 0.62 - l2));         // dark scenes are lifted so dark text reads
      g += sheen * 0.16;
      b = vec3(0.85, 0.30, 0.55);
    }
    // Hairline border, brightest at the top-left, catching the light again at the bottom-right.
    float e = max(-d, 0.0);
    float rim = 1.0 - clamp((e - 0.3 * uPx) / (1.0 * uPx), 0.0, 1.0);
    float ba = k < 0.5 ? mix(b.x, b.y, k / 0.5) : mix(b.y, b.z, (k - 0.5) / 0.5);
    g = mix(g, vec3(1.0), rim * ba);

    float cover = clamp(0.5 - d, 0.0, 1.0) * P.y;
    col = mix(col, clamp(g, 0.0, 1.0), cover);
  }
  gl_FragColor = vec4(col, 1.0);
}
"""
    }
}
