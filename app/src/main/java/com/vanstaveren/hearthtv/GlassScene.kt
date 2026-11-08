package com.vanstaveren.hearthtv

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * The frosted glass of the home screen's card, dock and tiles.
 *
 * The 4K background plays on the TV's own video plane (full quality, no GPU cost). Alongside it, a
 * tiny pre-blurred twin of the same clip (480×270, same frames) plays into this transparent GL layer,
 * which sits between the video and the UI and draws each pane from it: the moving scene shows through
 * deeply blurred, gently toned so text stays readable, with a soft sheen in the top-left, a hairline
 * border catching the light at the top-left and bottom-right, and a soft shadow underneath.
 * Everything outside the panes is left transparent, so the GPU only works where there is glass.
 *
 * If the shader can't be built on a TV, [onGlassFailed] lets the UI fall back to drawn glass.
 */
class GlassScene(context: Context) : GLSurfaceView(context), GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    /** One pane of glass, in this view's pixels. */
    data class Panel(
        val left: Float, val top: Float, val right: Float, val bottom: Float,
        val radius: Float, val alpha: Float,
        /** Kept for callers; the frost amount is fixed by the blurred twin. */
        val frost: Float,
        /** Shadow strength under the pane (0 = none). */
        val shadow: Float,
    )

    /** Main thread: the surface for the blurred twin's player is ready (or gone, when null). */
    var onSurface: ((Surface?) -> Unit)? = null
    /** Main thread: the glass shader isn't available on this TV. */
    var onGlassFailed: (() -> Unit)? = null

    @Volatile var glassOk = true
        private set
    @Volatile var dark = true
        set(v) { if (field != v) { field = v; requestRender() } }
    /** True once the blurred twin is drawing frames; until then panes use a plain tint. */
    @Volatile var hasScene = false
        set(v) { if (field != v) { field = v; requestRender() } }
    @Volatile private var panels: List<Panel> = emptyList()

    private var st: SurfaceTexture? = null
    private var surface: Surface? = null
    private var oesTex = 0
    private val stMat = FloatArray(16)
    private var bufW = 1; private var bufH = 1
    private val density = context.resources.displayMetrics.density
    private var prog = 0
    private lateinit var quad: FloatBuffer

    init {
        // A transparent layer above the video plane and below the UI.
        setZOrderMediaOverlay(true)
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 0, 0)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setRenderer(this)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    fun setPanels(list: List<Panel>) { if (list != panels) { panels = list; requestRender() } }

    /** Call from the activity's onPause/onResume (GLSurfaceView needs them). */
    fun hostPause() { onPause(); dropSurface() }
    fun hostResume() = onResume()

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        super.surfaceDestroyed(holder)
        dropSurface()
    }

    private fun dropSurface() {
        val s = surface; val t = st
        surface = null; st = null
        hasScene = false
        post { onSurface?.invoke(null) }
        try { s?.release() } catch (_: Exception) {}
        try { t?.release() } catch (_: Exception) {}
    }

    @Volatile private var fresh = false
    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) { fresh = true; requestRender() }

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
        prog = program(VERT_BOX, FRAG_PANE)
        if (prog == 0 && glassOk) { glassOk = false; post { onGlassFailed?.invoke() } }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        bufW = width; bufH = height
        Log.i(TAG, "glass layer ${width}x$height, ok=$glassOk")
    }

    override fun onDrawFrame(gl: GL10?) {
        val texture = st
        if (fresh && texture != null) {
            fresh = false
            try { texture.updateTexImage(); texture.getTransformMatrix(stMat) } catch (_: Exception) {}
        }
        GLES20.glViewport(0, 0, bufW, bufH)
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val ps = panels.filter { it.alpha > 0.004f }
        if (!glassOk || prog == 0 || ps.isEmpty()) return

        // Premultiplied alpha: the compositor blends this layer over the video.
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        GLES20.glUseProgram(prog)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(prog, "uScene"), 0)
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(prog, "uST"), 1, false, stMat, 0)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uHasScene"), if (hasScene) 1f else 0f)
        GLES20.glUniform2f(GLES20.glGetUniformLocation(prog, "uRes"), bufW.toFloat(), bufH.toFloat())
        val sx = bufW.toFloat() / maxOf(1, width); val sy = bufH.toFloat() / maxOf(1, height)
        val px = density * sx
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uPx"), px)
        GLES20.glUniform1f(GLES20.glGetUniformLocation(prog, "uDark"), if (dark) 1f else 0f)
        val uRect = GLES20.glGetUniformLocation(prog, "uRect")
        val uP = GLES20.glGetUniformLocation(prog, "uP")
        val uBox = GLES20.glGetUniformLocation(prog, "uBox")
        val a = GLES20.glGetAttribLocation(prog, "aPos")
        GLES20.glEnableVertexAttribArray(a)
        quad.position(0)
        GLES20.glVertexAttribPointer(a, 2, GLES20.GL_FLOAT, false, 0, quad)
        for (p in ps.take(MAX)) {
            // view pixels (y down) → buffer pixels (y up)
            val l = p.left * sx; val r = p.right * sx
            val t = bufH - p.top * sy; val b = bufH - p.bottom * sy
            GLES20.glUniform4f(uRect, (l + r) / 2, (t + b) / 2, (r - l) / 2, (t - b) / 2)
            GLES20.glUniform4f(uP, p.radius * sx, p.alpha, 0f, p.shadow)
            val m = if (p.shadow > 0f) 40f * px else 2f
            GLES20.glUniform4f(uBox, (l - m) / bufW * 2 - 1, (b - m) / bufH * 2 - 1, (r + m) / bufW * 2 - 1, (t + m) / bufH * 2 - 1)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
        GLES20.glDisableVertexAttribArray(a)
        GLES20.glDisable(GLES20.GL_BLEND)
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

        private val VERT_BOX = """
attribute vec2 aPos;
uniform vec4 uBox;
varying vec2 vUv;
void main() {
  vec2 p = mix(uBox.xy, uBox.zw, aPos * 0.5 + 0.5);
  vUv = p * 0.5 + 0.5;
  gl_Position = vec4(p, 0.0, 1.0);
}
"""

        /** One pane of frosted glass, output premultiplied. */
        private val FRAG_PANE = """
#extension GL_OES_EGL_image_external : require
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying vec2 vUv;
uniform samplerExternalOES uScene;
uniform mat4 uST;
uniform float uHasScene;
uniform vec2 uRes;
uniform float uPx;
uniform float uDark;
uniform vec4 uRect;
uniform vec4 uP;

float sdRR(vec2 p, vec2 c, vec2 h, float r) {
  vec2 q = abs(p - c) - h + r;
  return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

void main() {
  vec2 p = vUv * uRes;
  vec4 R = uRect;
  vec4 P = uP;
  float d = sdRR(p, R.xy, R.zw, P.x);

  // Outside the pane: just its soft shadow.
  if (d > 0.5) {
    if (P.w <= 0.0) discard;
    float ds = sdRR(p, R.xy - vec2(0.0, 6.0 * uPx), R.zw - vec2(2.0 * uPx), P.x);
    float sh = 1.0 - clamp((ds + 6.0 * uPx) / (30.0 * uPx), 0.0, 1.0);
    float a = sh * sh * P.w * P.y;
    if (a < 0.002) discard;
    gl_FragColor = vec4(0.0, 0.0, 0.0, a);
    return;
  }

  // Frosted glass: the scene behind (already blurred), gently toned for legibility.
  vec3 g = (uDark > 0.5) ? vec3(0.16, 0.18, 0.24) : vec3(0.80, 0.82, 0.86);
  if (uHasScene > 0.5) g = texture2D(uScene, (uST * vec4(vUv, 0.0, 1.0)).xy).rgb;
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
  gl_FragColor = vec4(clamp(g, 0.0, 1.0) * cover, cover);
}
"""
    }
}
