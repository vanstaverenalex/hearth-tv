package com.vanstaveren.hearthtv

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat

/** Hearth brand colors, shared with the Hearth dashboard. */
object Hearth {
    const val PURPLE = 0xFFA855F7.toInt()
    const val PINK = 0xFFF472B6.toInt()
    const val INK_DARK = 0xFF0B1726.toInt()
}

fun Context.dp(v: Float): Float = v * resources.displayMetrics.density
fun Context.dpi(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun View.dp(v: Float): Float = context.dp(v)
fun View.dpi(v: Float): Int = context.dpi(v)

object Fonts {
    private val cache = HashMap<Int, Typeface?>()
    fun get(ctx: Context, id: Int): Typeface? =
        cache.getOrPut(id) { try { ResourcesCompat.getFont(ctx, id) } catch (e: Exception) { null } }

    fun display(ctx: Context, weight: Int = 400): Typeface? = get(
        ctx, when {
            weight >= 600 -> R.font.fraunces_600
            weight <= 300 -> R.font.fraunces_300
            else -> R.font.fraunces_400
        }
    )

    fun body(ctx: Context, weight: Int = 600): Typeface? = get(
        ctx, when {
            weight >= 800 -> R.font.manrope_800
            weight >= 700 -> R.font.manrope_700
            weight >= 600 -> R.font.manrope_600
            else -> R.font.manrope_500
        }
    )
}

fun TextView.style(size: Float, face: Typeface?, color: Int, spacing: Float = 0f) {
    setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
    typeface = face
    setTextColor(color)
    letterSpacing = spacing
    includeFontPadding = false
}

fun View.roundClip(radiusDp: Float) {
    val r = dp(radiusDp)
    outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) =
            outline.setRoundRect(0, 0, view.width, view.height, r)
    }
    clipToOutline = true
}

/**
 * Liquid Glass panel: a faint fill, a diagonal sheen, and a bright refracted rim
 * (brightest top-left, dimmer bottom-right). Android 11 TVs can't blur live video,
 * so the glass stays clear and relies on the rim and sheen to read as glass.
 */
class GlassDrawable(private val ctx: Context, private val radiusDp: Float, var dark: Boolean = true) : Drawable() {
    var strength = 1f
        set(v) { field = v; invalidateSelf() }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        rebuild()
    }

    fun rebuild() {
        val b = bounds
        if (b.isEmpty) return
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        val a = { f: Float -> (f * 255 * strength).toInt().coerceIn(0, 255) }
        fill.color = if (dark) Color.argb(a(0.08f), 255, 255, 255) else Color.argb(a(0.30f), 255, 255, 255)
        sheen.shader = LinearGradient(
            b.left.toFloat(), b.top.toFloat(), b.left + w * 0.7f, b.top + h * 1.2f,
            intArrayOf(Color.argb(a(if (dark) 0.20f else 0.45f), 255, 255, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 0.55f), Shader.TileMode.CLAMP
        )
        rim.strokeWidth = ctx.dp(1.2f)
        rim.shader = LinearGradient(
            b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
            intArrayOf(
                Color.argb(a(if (dark) 0.62f else 0.98f), 255, 255, 255),
                Color.argb(a(if (dark) 0.14f else 0.55f), 255, 255, 255),
                Color.argb(a(if (dark) 0.32f else 0.80f), 255, 255, 255)
            ),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP
        )
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        val r = ctx.dp(radiusDp)
        val inset = rim.strokeWidth / 2
        rect.set(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset)
        canvas.drawRoundRect(rect, r, r, fill)
        canvas.drawRoundRect(rect, r, r, sheen)
        canvas.drawRoundRect(rect, r, r, rim)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** The Hearth focus glow: purple light from the top-left, pink from the bottom-right. */
class GlowView(context: Context, private val radiusDp: Float, private val padDp: Float) : View(context) {
    private val purple = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Hearth.PURPLE; alpha = 175
        maskFilter = BlurMaskFilter(context.dp(20f), BlurMaskFilter.Blur.NORMAL)
    }
    private val pink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Hearth.PINK; alpha = 165
        maskFilter = BlurMaskFilter(context.dp(22f), BlurMaskFilter.Blur.NORMAL)
    }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(200, 100, 230); alpha = 80
        maskFilter = BlurMaskFilter(context.dp(40f), BlurMaskFilter.Blur.NORMAL)
    }
    private val r = RectF()

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // blur mask filters need a software layer
        alpha = 0f
        isFocusable = false
    }

    override fun onDraw(canvas: Canvas) {
        val pad = dp(padDp)
        val rad = dp(radiusDp)
        val off = dp(5f)
        r.set(pad, pad, width - pad, height - pad)
        canvas.drawRoundRect(r, rad, rad, halo)
        r.set(pad - off, pad - off, width - pad - off, height - pad - off)
        canvas.drawRoundRect(r, rad, rad, purple)
        r.set(pad + off, pad + off, width - pad + off, height - pad + off)
        canvas.drawRoundRect(r, rad, rad, pink)
    }

    fun show(on: Boolean) {
        animate().alpha(if (on) 1f else 0f).setDuration(if (on) 220 else 160).start()
    }
}

/** Thin bright edge drawn over a tile so it reads as lit glass. */
fun rimDrawable(ctx: Context, radiusDp: Float, focused: Boolean, dark: Boolean): GradientDrawable =
    GradientDrawable().apply {
        cornerRadius = ctx.dp(radiusDp)
        setColor(Color.TRANSPARENT)
        val c = when {
            focused -> Color.argb(225, 255, 214, 240)
            dark -> Color.argb(40, 255, 255, 255)
            else -> Color.argb(110, 255, 255, 255)
        }
        setStroke(ctx.dpi(if (focused) 1.5f else 1f), c)
    }

/** Purple-to-pink fill used for focused rows and pills. */
fun hearthGradient(ctx: Context, radiusDp: Float, alpha: Float): GradientDrawable =
    GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT,
        intArrayOf(
            Color.argb((alpha * 255).toInt(), 168, 85, 247),
            Color.argb((alpha * 255).toInt(), 244, 114, 182)
        )
    ).apply { cornerRadius = ctx.dp(radiusDp) }

fun animateScale(v: View, to: Float) {
    v.animate().scaleX(to).scaleY(to).setDuration(180).start()
}

@Suppress("unused")
fun fade(v: View, to: Float, ms: Long = 200, end: (() -> Unit)? = null) {
    v.animate().alpha(to).setDuration(ms).withEndAction { end?.invoke() }.start()
}

@Suppress("unused")
fun pulse(v: View): ValueAnimator = ValueAnimator.ofFloat(1f, 1.03f, 1f).apply {
    duration = 260
    addUpdateListener { v.scaleX = it.animatedValue as Float; v.scaleY = it.animatedValue as Float }
    start()
}
