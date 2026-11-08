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
 * Category tabs shared by Apps and Live TV, so both screens look identical: just the names, no
 * box. The selected one is bold with a short Hearth-gradient underline that glides between tabs,
 * and glows while the tabs have focus. Left/right switch tabs.
 */
class SegmentedBar(context: Context) : View(context) {

    var dark = true
        set(v) { field = v; invalidate() }
    var onSelect: ((Int) -> Unit)? = null
    var items: List<String> = emptyList()
        set(v) { field = v; requestLayout(); invalidate() }
    var selected = 0
        private set

    /** Kept for callers; the tabs have no glass of their own. */
    var gpuGlass = false

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = context.dp(11.25f); textAlign = Paint.Align.CENTER; letterSpacing = 0.01f
    }
    private val bold = Fonts.body(context, 800)
    private val regular = Fonts.body(context, 600)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gap = context.dp(19.5f)          // space between names
    private val lineH = context.dp(2.25f)
    private val lineGap = context.dp(5.25f)      // name → underline
    private var lineX = 0f
    private var lineW = 0f
    private var anim: ValueAnimator? = null
    private val r = RectF()

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    /** Each name's slot, measured bold so nothing shifts when the selection moves. */
    private fun slots(): List<Float> { text.typeface = bold; return items.map { text.measureText(it) + gap } }
    private fun textW(i: Int): Float { text.typeface = bold; return text.measureText(items[i]) }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = (slots().sum() - gap + context.dp(16f)).toInt()   // a little room for the glow at the ends
        setMeasuredDimension(maxOf(w, 1), dpi(34f))
    }

    /** Underline position for tab [i]: centred under its name, as wide as the name. */
    private fun target(i: Int): Pair<Float, Float> {
        val ws = slots()
        if (ws.isEmpty()) return 0f to 0f
        val idx = i.coerceIn(0, ws.size - 1)
        var x = context.dp(8f) - gap / 2
        for (k in 0 until idx) x += ws[k]
        val cx = x + ws[idx] / 2
        val tw = textW(idx)
        return (cx - tw / 2) to tw
    }

    fun select(i: Int, animate: Boolean = true) {
        if (items.isEmpty()) return
        val idx = i.coerceIn(0, items.size - 1)
        selected = idx
        val (tx, tw) = target(idx)
        anim?.cancel()
        if (!animate || lineW == 0f) { lineX = tx; lineW = tw; invalidate(); return }
        val sx = lineX; val sw = lineW
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 280; interpolator = Ease.out
            addUpdateListener { v -> val f = v.animatedValue as Float; lineX = sx + (tx - sx) * f; lineW = sw + (tw - sw) * f; invalidate() }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        select(selected, animate = false)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
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
        if (items.isEmpty()) return
        val ink = if (dark) Color.WHITE else Hearth.INK_DARK
        val h = height.toFloat()
        // name + underline, centred vertically as one unit
        text.typeface = bold
        val capH = -text.ascent() * 0.72f
        val block = capH + lineGap + lineH
        val base = (h - block) / 2 + capH
        var x = context.dp(8f) - gap / 2
        slots().forEachIndexed { i, w ->
            val on = i == selected
            text.typeface = if (on) bold else regular
            text.color = if (on) ink else Color.argb(158, Color.red(ink), Color.green(ink), Color.blue(ink))
            canvas.drawText(items[i], x + w / 2, base, text)
            x += w
        }
        // the underline
        val top = base + lineGap
        r.set(lineX, top, lineX + lineW, top + lineH)
        line.shader = LinearGradient(r.left, 0f, r.right, 0f, Hearth.PURPLE, Hearth.PINK, Shader.TileMode.CLAMP)
        if (isFocused) line.setShadowLayer(context.dp(6f), 0f, 0f, Color.argb(if (dark) 215 else 150, 236, 110, 210))
        else line.clearShadowLayer()
        canvas.drawRoundRect(r, lineH / 2, lineH / 2, line)
    }
}
