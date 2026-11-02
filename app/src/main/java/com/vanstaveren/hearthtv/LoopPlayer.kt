package com.vanstaveren.hearthtv

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import java.io.File

/**
 * Full-screen looping video background, tuned for TV performance.
 * - Uses a SurfaceView, so the TV's hardware video plane shows the 4K video directly
 *   (no extra GPU copy competing with the UI animations).
 * - Clips are seamless loops; MediaPlayer loops them in place.
 * - Fills the screen edge to edge (center-crop).
 * - Changing scenes: a soft veil fades in, the clip swaps underneath, the veil fades out.
 */
class LoopPlayer(context: Context) : FrameLayout(context), SurfaceHolder.Callback {

    private val surfaceView = SurfaceView(context)
    private val veil = View(context)
    private var player: MediaPlayer? = null
    private var holderReady = false
    private var pending: File? = null
    var current: File? = null
        private set
    private var videoW = 16
    private var videoH = 9

    var veilColor: Int = 0xFF080C1E.toInt()
        set(v) { field = v; veil.setBackgroundColor(v) }

    init {
        isFocusable = false
        surfaceView.holder.addCallback(this)
        addView(surfaceView, LayoutParams(-1, -1))
        veil.setBackgroundColor(veilColor)
        addView(veil, LayoutParams(-1, -1))
    }

    fun play(file: File) {
        if (file == current && player != null) { resume(); return }
        if (!holderReady) { pending = file; return }
        if (player == null) { start(file); return }
        // Dissolve: veil in, swap, veil out once the new clip is drawing.
        veil.animate().cancel()
        veil.animate().alpha(1f).setDuration(380).setInterpolator(Ease.inOut).withEndAction { start(file) }.start()
    }

    fun pause() { try { player?.pause() } catch (_: Exception) {} }
    fun resume() { try { if (player?.isPlaying == false) player?.start() } catch (_: Exception) {} }

    private fun start(file: File) {
        release()
        current = file
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
                    veil.animate().cancel()
                    veil.animate().alpha(0f).setDuration(1100).setInterpolator(Ease.inOut).start()
                }
                false
            }
            mp.setOnErrorListener { _, what, extra ->
                Log.w("HearthBg", "playback error $what/$extra on ${file.name}")
                file.delete(); current = null; true
            }
            mp.setOnPreparedListener { it.start() }
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.w("HearthBg", "can't play ${file.name}: ${e.message}")
            release()
        }
    }

    private fun release() {
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
        veil.layout(0, 0, w, h)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        holderReady = true
        val f = pending ?: current
        pending = null
        if (f != null) { current = null; veil.alpha = 1f; start(f) }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        holderReady = false
        pending = current
        release()
        veil.animate().cancel(); veil.alpha = 1f
    }
}
