package com.vanstaveren.hearthtv

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import android.view.Surface
import android.view.View
import android.widget.FrameLayout
import java.io.File

/**
 * Full-screen looping video background.
 * - Drawn by [GlassScene] (OpenGL, at the panel's 4K resolution), which also renders the
 *   home screen's Liquid Glass panes from the same video frames.
 * - Clips are seamless loops; MediaPlayer loops them in place.
 * - Fills the screen edge to edge (center-crop, done in the shader).
 * - Changing scenes: a soft veil fades in, the clip swaps underneath, the veil fades out.
 */
class LoopPlayer(context: Context) : FrameLayout(context) {

    val scene = GlassScene(context)
    private var surface: Surface? = null
    private val veil = View(context)
    private var player: MediaPlayer? = null
    private var holderReady = false
    private var pending: File? = null
    var current: File? = null
        private set
    private var errors = 0

    var veilColor: Int = 0xFF080C1E.toInt()
        set(v) { field = v; veil.setBackgroundColor(v) }

    init {
        isFocusable = false
        scene.onSurface = { s -> if (s != null) surfaceReady(s) else surfaceGone() }
        addView(scene, LayoutParams(-1, -1))
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

    /**
     * Let go of the video decoder completely. Called before Hearth opens another app: TV chips have
     * only a few hardware decoders, and an app that can't get one when it starts (Hulu) shows an error.
     */
    fun stop() {
        release()
        veil.animate().cancel(); veil.alpha = 1f
    }

    /** Pick up again after [stop] or [pause]; a released clip restarts behind the veil and fades in. */
    fun resume() {
        val p = player
        if (p == null) { current?.let { f -> if (holderReady) start(f) else pending = f }; return }
        try { if (!p.isPlaying) p.start() } catch (_: Exception) {}
    }

    private fun start(file: File) {
        release()
        current = file
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setDataSource(file.absolutePath)
            mp.setSurface(surface)
            mp.isLooping = true
            mp.setVolume(0f, 0f)
            mp.setScreenOnWhilePlaying(false)
            mp.setOnVideoSizeChangedListener { _, w, h -> scene.setVideoSize(w, h) }
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
                else postDelayed({ if (player == null && current == file && holderReady && isShown) start(file) }, 1500)
                true
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

    /** Activity lifecycle hooks (the GL view needs them). */
    fun hostPause() = scene.hostPause()
    fun hostResume() = scene.hostResume()

    private fun surfaceReady(s: Surface) {
        surface = s
        holderReady = true
        val f = pending ?: current
        pending = null
        if (f != null) { current = null; veil.alpha = 1f; start(f) }
    }

    private fun surfaceGone() {
        holderReady = false
        pending = current
        release()
        surface = null
        veil.animate().cancel(); veil.alpha = 1f
    }
}
