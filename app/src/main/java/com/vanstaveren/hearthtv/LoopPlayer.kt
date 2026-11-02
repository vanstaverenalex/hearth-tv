package com.vanstaveren.hearthtv

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import android.widget.ImageView
import java.io.File

/**
 * Full-screen looping video background.
 * - Clips are pre-made seamless loops, and MediaPlayer loops them in place (no black flash).
 * - Fills the screen edge to edge (center-crop), never letterboxed.
 * - Changing scenes dissolves: the last frame is frozen on top, the new clip starts
 *   underneath, then the frozen frame fades away.
 */
class LoopPlayer(context: Context) : FrameLayout(context), TextureView.SurfaceTextureListener {

    private val texture = TextureView(context)
    private val freeze = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var pending: File? = null
    var current: File? = null
        private set
    private var videoW = 0
    private var videoH = 0

    init {
        isFocusable = false
        texture.surfaceTextureListener = this
        texture.alpha = 0f
        addView(texture, LayoutParams(-1, -1))
        addView(freeze, LayoutParams(-1, -1))
    }

    fun play(file: File) {
        if (file == current && player != null) { player?.start(); return }
        if (surface == null) { pending = file; return }
        start(file)
    }

    fun pause() { try { player?.pause() } catch (_: Exception) {} }
    fun resume() { try { player?.start() } catch (_: Exception) {} }

    private fun start(file: File) {
        // Freeze what's on screen so the change dissolves instead of cutting.
        if (player != null && texture.alpha > 0f) {
            try {
                freeze.setImageBitmap(texture.getBitmap(960, 540))
                freeze.animate().cancel()
                freeze.alpha = 1f
            } catch (_: Exception) {}
        }
        release()
        current = file
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setDataSource(file.absolutePath)
            mp.setSurface(surface)
            mp.isLooping = true
            mp.setVolume(0f, 0f)
            mp.setOnVideoSizeChangedListener { _, w, h -> videoW = w; videoH = h; fit() }
            mp.setOnInfoListener { _, what, _ ->
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    texture.animate().alpha(1f).setDuration(700).start()
                    freeze.animate().alpha(0f).setDuration(1400).withEndAction { freeze.setImageDrawable(null) }.start()
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

    /** Center-crop the video to fill the view. */
    private fun fit() {
        val vw = width.toFloat(); val vh = height.toFloat()
        if (vw == 0f || vh == 0f || videoW == 0 || videoH == 0) return
        val scale = maxOf(vw / videoW, vh / videoH)
        val sx = videoW * scale / vw
        val sy = videoH * scale / vh
        texture.setTransform(Matrix().apply { setScale(sx, sy, vw / 2f, vh / 2f) })
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh); fit()
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        surface = Surface(st)
        (pending ?: current)?.let { pending = null; current = null; start(it) }
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = fit()

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        val keep = current
        release()
        surface?.release(); surface = null
        pending = keep
        texture.alpha = 0f
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
}
