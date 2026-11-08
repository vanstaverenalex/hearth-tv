package com.vanstaveren.hearthtv

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Grid that scrolls like tvOS: when focus nears the top or bottom, the rows glide on a spring so the
 * selected row settles toward the middle. Repeated presses add to the same glide (no restart, no jolt),
 * so holding the remote's arrow gives one continuous, fluid scroll.
 */
class SmoothGridLayoutManager(context: Context, span: Int) : GridLayoutManager(context, span) {

    private val spring = Spring(0.0, 15.0)
    private var applied = 0
    private var running = false
    private var last = 0L

    override fun requestChildRectangleOnScreen(
        parent: RecyclerView, child: View, rect: Rect, immediate: Boolean, focusedChildVisible: Boolean,
    ): Boolean {
        val parentTop = parent.paddingTop
        val parentBottom = parent.height - parent.paddingBottom
        val visible = parentBottom - parentTop
        // Where the child will be once the glide already in flight finishes.
        val pending = (spring.target - spring.x).toInt()
        val childTop = child.top + rect.top - child.scrollY - pending
        val childBottom = childTop + rect.height()
        val bandTop = parentTop + visible * 0.18f
        val bandBottom = parentBottom - visible * 0.18f
        val dy = when {
            childTop < bandTop -> (childTop - (parentTop + visible * 0.33f)).toInt()
            childBottom > bandBottom -> (childBottom - (parentBottom - visible * 0.33f)).toInt()
            else -> 0
        }
        if (dy == 0) return pending != 0
        if (immediate) { parent.scrollBy(0, dy); return true }
        spring.target += dy
        if (!running) {
            running = true; last = SystemClock.uptimeMillis()
            parent.postOnAnimation(object : Runnable {
                override fun run() {
                    val now = SystemClock.uptimeMillis()
                    spring.step(((now - last).coerceIn(0, 50)) / 1000.0); last = now
                    val want = Math.round(spring.x).toInt()
                    if (want != applied) { parent.scrollBy(0, want - applied); applied = want }
                    if (spring.moving && parent.isAttachedToWindow) parent.postOnAnimation(this)
                    else { running = false; spring.snap(0.0); applied = 0 }
                }
            })
        }
        return true
    }
}

/**
 * Full-screen grid for the app drawer. Rows draw edge to edge (so focus glows are never cut off),
 * then fade out softly under the header and above the hint instead of being clipped by a box.
 * Fades only appear once there's something to scroll to, and grow in with the scroll distance,
 * so nothing pops. A slim indicator shows the position.
 */
class GlideGrid(context: Context) : RecyclerView(context) {
    var dark = true
        set(v) { field = v; invalidate() }
    /** Content above this y (px) is fully faded; it becomes fully visible by [fadeTopEnd]. */
    var fadeTopStart = 0f
    var fadeTopEnd = 0f
    var fadeBottom = context.dp(64f)

    private val mask = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    init {
        isVerticalFadingEdgeEnabled = false
        isVerticalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
    }

    /** The grid's fade band (view px) for the GPU glass under its cards; see GuideView.glassFade. */
    fun glassFade(out: FloatArray) {
        val off = computeVerticalScrollOffset().toFloat()
        val range = (computeVerticalScrollRange() - computeVerticalScrollExtent()).toFloat()
        val ramp = context.dp(48f)
        val topK = (off / ramp).coerceIn(0f, 1f)
        val botK = ((range - off) / ramp).coerceIn(0f, 1f)
        if (topK > 0.01f) { out[0] = fadeTopStart; out[1] = fadeTopEnd } else { out[0] = -1e6f; out[1] = -1e6f }
        if (botK > 0.01f) { val y0 = height - paddingBottom - fadeBottom * 0.2f; out[2] = y0; out[3] = y0 + fadeBottom }
        else { out[2] = 1e6f; out[3] = 1e6f }
    }

    /** Called after each draw, so the glass can follow the cards exactly. */
    var onGlass: (() -> Unit)? = null

    override fun dispatchDraw(canvas: Canvas) {
        drawGrid(canvas)
        onGlass?.invoke()
    }

    private fun drawGrid(canvas: Canvas) {
        val off = computeVerticalScrollOffset().toFloat()
        val range = (computeVerticalScrollRange() - computeVerticalScrollExtent()).toFloat()
        val ramp = context.dp(48f)
        val topK = (off / ramp).coerceIn(0f, 1f)
        val botK = ((range - off) / ramp).coerceIn(0f, 1f)
        if (topK == 0f && botK == 0f) { super.dispatchDraw(canvas); drawThumb(canvas, off, range); return }
        val save = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        super.dispatchDraw(canvas)
        if (topK > 0f) {
            val a = (255 * topK).toInt()
            mask.shader = null; mask.color = Color.argb(a, 0, 0, 0)
            canvas.drawRect(0f, 0f, width.toFloat(), fadeTopStart, mask)
            mask.shader = LinearGradient(0f, fadeTopStart, 0f, fadeTopEnd, Color.argb(a, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, fadeTopStart, width.toFloat(), fadeTopEnd, mask)
        }
        if (botK > 0f) {
            val a = (255 * botK).toInt()
            val y0 = height - paddingBottom - fadeBottom * 0.2f
            val y1 = y0 + fadeBottom
            mask.shader = LinearGradient(0f, y0, 0f, y1, Color.TRANSPARENT, Color.argb(a, 0, 0, 0), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, y0, width.toFloat(), y1, mask)
            mask.shader = null; mask.color = Color.argb(a, 0, 0, 0)
            canvas.drawRect(0f, y1, width.toFloat(), height.toFloat(), mask)
        }
        mask.shader = null
        canvas.restoreToCount(save)
        drawThumb(canvas, off, range)
    }

    private fun drawThumb(canvas: Canvas, off: Float, range: Float) {
        if (range <= 1f) return
        val x = width - paddingRight + context.dp(22f)
        val top = fadeTopEnd; val bottom = height - paddingBottom.toFloat()
        val span = bottom - top
        val extent = computeVerticalScrollExtent().toFloat()
        val h = maxOf(context.dp(24f), span * extent / (extent + range))
        val y = top + (span - h) * (off / range).coerceIn(0f, 1f)
        val ink = if (dark) Color.WHITE else 0xFF0B1726.toInt()
        thumb.color = Color.argb(24, Color.red(ink), Color.green(ink), Color.blue(ink))
        r.set(x - context.dp(1.5f), top, x + context.dp(1.5f), bottom); canvas.drawRoundRect(r, r.width(), r.width(), thumb)
        thumb.color = Color.argb(120, Color.red(ink), Color.green(ink), Color.blue(ink))
        r.set(x - context.dp(1.5f), y, x + context.dp(1.5f), y + h); canvas.drawRoundRect(r, r.width(), r.width(), thumb)
    }
}
