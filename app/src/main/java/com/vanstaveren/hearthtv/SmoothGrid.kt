package com.vanstaveren.hearthtv

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
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

/** Soft top/bottom fades and a slim glass scroll indicator for scrolling lists. */
fun RecyclerView.polishScrolling(dark: Boolean) {
    isVerticalFadingEdgeEnabled = true
    setFadingEdgeLength(context.dpi(36f))
    isVerticalScrollBarEnabled = true
    scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
    scrollBarFadeDuration = 600
    scrollBarDefaultDelayBeforeFade = 900
    if (Build.VERSION.SDK_INT >= 29) {
        val c = if (dark) 0x8CFFFFFF.toInt() else 0x8C0B1726.toInt()
        verticalScrollbarThumbDrawable = GradientDrawable().apply { cornerRadius = context.dp(2f); setColor(c) }
        verticalScrollbarTrackDrawable = GradientDrawable().apply {
            cornerRadius = context.dp(2f); setColor(if (dark) 0x1AFFFFFF else 0x1A0B1726)
        }
        scrollBarSize = context.dpi(3f)
    }
}
