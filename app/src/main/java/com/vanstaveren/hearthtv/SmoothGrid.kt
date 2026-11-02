package com.vanstaveren.hearthtv

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Grid that scrolls like tvOS: when focus moves near the top or bottom, the rows glide
 * (eased, ~320ms) so the selected row settles toward the middle instead of snapping to the edge.
 */
class SmoothGridLayoutManager(context: Context, span: Int) : GridLayoutManager(context, span) {

    override fun requestChildRectangleOnScreen(
        parent: RecyclerView, child: View, rect: Rect, immediate: Boolean, focusedChildVisible: Boolean,
    ): Boolean {
        val parentTop = parent.paddingTop
        val parentBottom = parent.height - parent.paddingBottom
        val visible = parentBottom - parentTop
        val childTop = child.top + rect.top - child.scrollY
        val childBottom = childTop + rect.height()
        // Only move when the row leaves the comfortable middle band.
        val bandTop = parentTop + visible * 0.18f
        val bandBottom = parentBottom - visible * 0.18f
        val dy = when {
            childTop < bandTop -> (childTop - (parentTop + visible * 0.33f)).toInt()
            childBottom > bandBottom -> (childBottom - (parentBottom - visible * 0.33f)).toInt()
            else -> 0
        }
        if (dy == 0) return false
        if (immediate) parent.scrollBy(0, dy) else parent.smoothScrollBy(0, dy, Ease.out, 320)
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
