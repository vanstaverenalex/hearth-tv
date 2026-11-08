package com.vanstaveren.hearthtv

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import java.io.File

/**
 * Full-screen looping video background.
 * - The 4K clip plays on a SurfaceView, so the TV's hardware video plane shows it at full 4K
 *   quality with no GPU cost.
 * - Its small pre-blurred twin plays in step into [scene], which draws the frosted glass panes.
 * - Clips are seamless loops; MediaPlayer loops them in place. Center-cropped to fill the screen.
 * - Changing scenes: a soft veil fades in, the clip swaps underneath, the veil fades out.
 */
class LoopPlayer(context: Context) : FrameLayout(context), SurfaceHolder.Callback {

    private val surfaceView = SurfaceView(context)
    val scene = GlassScene(context)
    private val veil = View(context)
    private var player: MediaPlayer? = null
    private var glassPlayer: MediaPlayer? = null
    private var holderReady = false
    private var glassSurface: Surface? = null
    private var pending: File? = null
    private var pendingGlass: File? = null
    var current: File? = null
        private set
    /** Whether the frosted glass has the blurred twin of the current clip. */
    val hasGlass get() = currentGlass != null
    private var currentGlass: File? = null
    private var errors = 0
    private var videoW = 16
    private var videoH = 9

    var veilColor: Int = 0xFF080C1E.toInt()
        set(v) { field = v; veil.setBackgroundColor(v) }

    init {
        isFocusable = false
        surfaceView.holder.addCallback(this)
        addView(surfaceView, LayoutParams(-1, -1))
        scene.onSurface = { s -> glassSurface = s; if (s != null) startGlass() else releaseGlass() }
        addView(scene, LayoutParams(-1, -1))
        veil.setBackgroundColor(veilColor)
        addView(veil, LayoutParams(-1, -1))
    }

    /** Play a clip; [glass] is its blurred twin for the frosted panes (null if not on the TV yet). */
    fun play(file: File, glass: File? = null) {
        if (file == current && player != null) {
            if (glass != null && currentGlass == null) { currentGlass = glass; startGlass() }
            resume(); return
        }
        if (!holderReady) { pending = file; pendingGlass = glass; return }
        if (player == null) { start(file, glass); return }
        // Dissolve: veil in, swap, veil out once the new clip is drawing.
        veil.animate().cancel()
        veil.animate().alpha(1f).setDuration(380).setInterpolator(Ease.inOut).withEndAction { start(file, glass) }.start()
    }

    fun pause() {
        try { player?.pause() } catch (_: Exception) {}
        try { glassPlayer?.pause() } catch (_: Exception) {}
    }

    /**
     * Let go of the video decoders completely. Called before Hearth opens another app: TV chips have
     * only a few hardware decoders, and an app that can't get one when it starts (Hulu) shows an error.
     */
    fun stop() {
        release()
        veil.animate().cancel(); veil.alpha = 1f
    }

    /** Pick up again after [stop] or [pause]; a released clip restarts behind the veil and fades in. */
    fun resume() {
        val p = player
        if (p == null) {
            current?.let { f -> if (holderReady) start(f, currentGlass) else { pending = f; pendingGlass = currentGlass } }
            return
        }
        try { if (!p.isPlaying) p.start() } catch (_: Exception) {}
        try { glassPlayer?.let { if (!it.isPlaying) it.start() } } catch (_: Exception) {}
    }

    /** Activity lifecycle hooks (the GL view needs them). */
    fun hostPause() = scene.hostPause()
    fun hostResume() = scene.hostResume()

    private fun start(file: File, glass: File?) {
        release()
        current = file
        currentGlass = glass
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setDataSource(file.absolutePath)
            mp.setDisplay(surfaceView.holder)
            mp.isLooping = true
            mp.setVolume(0f, 0f)
            mp.setScreenOnWhilePlaying(false)
            mp.setOnVideoSizeChangedListener { _, w, h -> if (w > 0 && h > 0) { videoW = w; videoH = h; requestLayout() } }
            mp.setOnInfoListener { _, what, _ ->
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    errors = 0
                    veil.animate().cancel()
                    veil.animate().alpha(0f).setDuration(1100).setInterpolator(Ease.inOut).start()
                }
                false
            }
            mp.setOnErrorListener { _, what, extra ->
                // Usually the decoder was taken by another app; keep the file and try again shortly.
                Log.w("HearthBg", "playback error $what/$extra on ${file.name}")
                release()
                errors++
                if (errors >= 3) { file.delete(); current = null; errors = 0 }   // really broken: fetch it again later
                else postDelayed({ if (player == null && current == file && holderReady && isShown) start(file, currentGlass) }, 1500)
                true
            }
            mp.setOnPreparedListener { it.start(); startGlass() }
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.w("HearthBg", "can't play ${file.name}: ${e.message}")
            release()
        }
    }

    /** Start the blurred twin in step with the 4K clip (once both it and its GL surface are ready). */
    private fun startGlass() {
        val g = currentGlass ?: return
        val s = glassSurface ?: return
        val main = player ?: return
        if (glassPlayer != null) return
        val gp = MediaPlayer()
        glassPlayer = gp
        try {
            gp.setDataSource(g.absolutePath)
            gp.setSurface(s)
            gp.isLooping = true
            gp.setVolume(0f, 0f)
            gp.setOnInfoListener { _, what, _ ->
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) scene.hasScene = true
                false
            }
            gp.setOnErrorListener { _, what, extra ->
                Log.w("HearthBg", "glass twin error $what/$extra on ${g.name}")
                releaseGlass(); true
            }
            gp.setOnPreparedListener {
                try { seekGlass(it, main.currentPosition + 60) } catch (_: Exception) {}
                it.start()
                removeCallbacks(resync); postDelayed(resync, 3000)
            }
            gp.prepareAsync()
        } catch (e: Exception) {
            Log.w("HearthBg", "can't play glass twin: ${e.message}")
            releaseGlass()
        }
    }

    /** Keep the blurred twin within a frame or two of the 4K clip (they can drift over many loops). */
    private val resync = object : Runnable {
        override fun run() {
            val m = player; val g = glassPlayer
            if (m == null || g == null) return
            try {
                if (m.isPlaying && g.isPlaying) {
                    val dur = maxOf(1, m.duration)
                    var diff = (g.currentPosition - m.currentPosition) % dur
                    if (diff > dur / 2) diff -= dur
                    if (diff < -dur / 2) diff += dur
                    if (kotlin.math.abs(diff) > 120) seekGlass(g, m.currentPosition + 40)
                }
            } catch (_: Exception) {}
            postDelayed(this, 3000)
        }
    }

    private fun seekGlass(g: MediaPlayer, ms: Int) {
        if (android.os.Build.VERSION.SDK_INT >= 26) g.seekTo(ms.toLong(), MediaPlayer.SEEK_CLOSEST) else g.seekTo(ms)
    }

    private fun releaseGlass() {
        removeCallbacks(resync)
        try { glassPlayer?.release() } catch (_: Exception) {}
        glassPlayer = null
        scene.hasScene = false
    }

    private fun release() {
        releaseGlass()
        try { player?.release() } catch (_: Exception) {}
        player = null
    }

    /** Size the video surface to cover the screen; the overflow is off-screen. */
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l; val h = b - t
        val scale = maxOf(w.toFloat() / videoW, h.toFloat() / videoH)
        val sw = (videoW * scale).toInt(); val sh = (videoH * scale).toInt()
        val x = (w - sw) / 2; val y = (h - sh) / 2
        surfaceView.layout(x, y, x + sw, y + sh)
        scene.layout(0, 0, w, h)
        veil.layout(0, 0, w, h)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        holderReady = true
        val f = pending ?: current
        val g = if (pending != null) pendingGlass else currentGlass
        pending = null; pendingGlass = null
        if (f != null) { current = null; veil.alpha = 1f; start(f, g) }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        holderReady = false
        pending = current; pendingGlass = currentGlass
        release()
        veil.animate().cancel(); veil.alpha = 1f
    }
}
