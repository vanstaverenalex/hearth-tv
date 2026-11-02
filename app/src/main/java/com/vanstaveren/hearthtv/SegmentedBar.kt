package com.vanstaveren.hearthtv

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.KeyEvent
import android.view.View

/**
 * Glass tab bar shared by Apps and Live TV, so both screens look identical.
 * One capsule, labels drawn inline, a selection pill that glides between tabs,
 * and the Hearth gradient when the bar has focus. Left/right switch tabs.
 */
class SegmentedBar(context: Context) : View(context) {

    var dark = true
        set(v) { field = v; glass.dark = v; glass.rebuild(); invalidate() }
    var onSelect: ((Int) -> Unit)? = null
    var items: List<String> = emptyList()
        set(v) { field = v; requestLayout(); invalidate() }
    var selected = 0
        private set

    private val glass = GlassDrawable(context, 18f, true)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = context.dp(11f); typeface = Fonts.body(context, 700); textAlign = Paint.Align.CENTER
    }
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = context.dp(1f) }
    private val padX = context.dp(15f)
    private val inset = context.dp(3f)
    private var pillX = 0f
    private var pillW = 0f
    private var anim: ValueAnimator? = null
    private val r = RectF()

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    private fun widths() = items.map { text.measureText(it) + padX * 2 }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = (widths().sum() + inset * 2).toInt()
        setMeasuredDimension(w, dpi(34f))
    }

    private fun target(i: Int): Pair<Float, Float> {
        val ws = widths()
        var x = inset
        for (k in 0 until i.coerceIn(0, ws.size - 1)) x += ws[k]
        return x to (ws.getOrNull(i) ?: 0f)
    }

    fun select(i: Int, animate: Boolean = true) {
        if (items.isEmpty()) return
        val idx = i.coerceIn(0, items.size - 1)
        selected = idx
        val (tx, tw) = target(idx)
        anim?.cancel()
        if (!animate || pillW == 0f) { pillX = tx; pillW = tw; invalidate(); return }
        val sx = pillX; val sw = pillW
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 260; interpolator = Ease.out
            addUpdateListener { v -> val f = v.animatedValue as Float; pillX = sx + (tx - sx) * f; pillW = sw + (tw - sw) * f; invalidate() }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        glass.setBounds(0, 0, w, h); glass.rebuild()
        select(selected, animate = false)
    }

    private var focusAt = 0L

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (gainFocus) focusAt = android.os.SystemClock.uptimeMillis()
        invalidate()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (selected > 0) { select(selected - 1); onSelect?.invoke(selected); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (selected < items.size - 1) { select(selected + 1); onSelect?.invoke(selected); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDraw(canvas: Canvas) {
        glass.draw(canvas)
        val h = height.toFloat()
        val rad = (h - inset * 2) / 2
        r.set(pillX, inset, pillX + pillW, h - inset)
        if (isFocused) {
            pill.shader = LinearGradient(r.left, 0f, r.right, 0f,
                Color.argb(140, 168, 85, 247), Color.argb(140, 244, 114, 182), Shader.TileMode.CLAMP)
            canvas.drawRoundRect(r, rad, rad, pill)
            ShimmerRim.paint(context, canvas, r, rad, focusAt)
            postInvalidateOnAnimation()
        } else {
            pill.shader = null
            pill.color = if (dark) Color.argb(60, 255, 255, 255) else Color.WHITE
            canvas.drawRoundRect(r, rad, rad, pill)
        }
        val ink = if (dark) Color.WHITE else Hearth.INK_DARK
        var x = inset
        val base = h / 2 - (text.descent() + text.ascent()) / 2
        widths().forEachIndexed { i, w ->
            text.color = when {
                i == selected && isFocused -> Color.WHITE
                i == selected -> ink
                else -> Color.argb(165, Color.red(ink), Color.green(ink), Color.blue(ink))
            }
            canvas.drawText(items[i], x + w / 2, base, text)
            x += w
        }
    }
}
