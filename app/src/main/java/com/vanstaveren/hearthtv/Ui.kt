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

    /** Inter Light, used only for the big clock: clean, modern, with even-width digits. */
    fun clock(ctx: Context): Typeface? = get(ctx, R.font.inter_clock)

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
 * Liquid Glass panel, drawn once into a dithered bitmap at the screen's real resolution
 * (TV GPUs band soft gradients badly, so nothing here is drawn live).
 *
 * Layers, back to front: soft drop shadow (panels only) · tinted body with a top-to-bottom falloff ·
 * a top-left sheen · fine frosted grain · a specular band along the top · a soft inner edge glow ·
 * a refracted hairline rim (bright top-left, dimmer sides, catching light again bottom-right) ·
 * an outer definition line so the edge reads over bright or busy video.
 * Android 11 can't blur the live video behind it, so the frosting is suggested by tint, grain and light.
 */
class GlassDrawable(
    private val ctx: Context,
    private val radiusDp: Float,
    dark: Boolean = true,
    /**
     * Big floating panels (info card, dock): deeper tint + a soft shadow. The shadow lives inside the
     * view's own bounds, so the view needs [SHADOW_DP] of extra padding on every side (see [shadowPad]).
     */
    private val panel: Boolean = false,
) : Drawable() {
    var dark: Boolean = dark
        set(v) { if (field != v) { field = v; invalidateSelf() } }
    var strength = 1f
        set(v) { if (field != v) { field = v; invalidateSelf() } }

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val pad get() = if (panel) shadowPad(ctx) else 0

    companion object {
        const val SHADOW_DP = 20f
        fun shadowPad(ctx: Context) = ctx.dpi(SHADOW_DP)
    }

    override fun onBoundsChange(bounds: Rect) { super.onBoundsChange(bounds); invalidateSelf() }

    /** Kept for callers that change [dark] and want the new look right away. */
    fun rebuild() = invalidateSelf()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        if (b.width() <= 2 * pad || b.height() <= 2 * pad) return
        val bm = GlassArt.get(ctx, b.width() - 2 * pad, b.height() - 2 * pad, radiusDp, dark, panel, strength, pad)
        canvas.drawBitmap(bm, b.left.toFloat(), b.top.toFloat(), paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

object GlassArt {
    private val cache = object : LinkedHashMap<String, android.graphics.Bitmap>(32, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, android.graphics.Bitmap>?) = size > 48
    }
    private var grain: android.graphics.Bitmap? = null

    @Synchronized
    fun get(ctx: Context, w: Int, h: Int, rDp: Float, dark: Boolean, panel: Boolean, s: Float, pad: Int): android.graphics.Bitmap {
        val key = "$w:$h:$rDp:$dark:$panel:$s:$pad"
        return cache[key] ?: render(ctx, w, h, rDp, dark, panel, s, pad).also { cache[key] = it }
    }

    /** Fine monochrome noise; tiled across the glass it reads as frosting and hides gradient banding. */
    private fun grain(): android.graphics.Bitmap = grain ?: run {
        val n = 128
        val rnd = java.util.Random(7)
        val px = IntArray(n * n) { if (rnd.nextBoolean()) Color.argb(rnd.nextInt(256), 255, 255, 255) else Color.argb(rnd.nextInt(256), 0, 0, 0) }
        android.graphics.Bitmap.createBitmap(px, n, n, android.graphics.Bitmap.Config.ARGB_8888).also { grain = it }
    }

    private fun render(ctx: Context, w: Int, h: Int, rDp: Float, dark: Boolean, panel: Boolean, s: Float, pad: Int): android.graphics.Bitmap {
        val bmp = android.graphics.Bitmap.createBitmap(w + 2 * pad, h + 2 * pad, android.graphics.Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.translate(pad.toFloat(), pad.toFloat())
        fun dp(v: Float) = ctx.dp(v)
        fun a(f: Float) = (f * 255 * s).toInt().coerceIn(0, 255)
        fun white(f: Float) = Color.argb(a(f), 255, 255, 255)
        val r = minOf(dp(rDp), h / 2f, w / 2f)
        val rect = RectF(0f, 0f, w.toFloat(), h.toFloat())
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)

        // 1. Shadow, outside the glass only
        if (panel && pad > 0) {
            p.color = if (dark) Color.argb(a(0.42f), 0, 2, 12) else Color.argb(a(0.16f), 24, 34, 70)
            p.maskFilter = BlurMaskFilter(dp(11f), BlurMaskFilter.Blur.NORMAL)
            c.drawRoundRect(RectF(dp(4f), dp(5f), w - dp(4f), h + dp(5f)), r, r, p)
            p.maskFilter = null
            p.xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
            c.drawRoundRect(rect, r, r, p)
            p.xfermode = null
        }

        // 2. Body
        val clip = android.graphics.Path().apply { addRoundRect(rect, r, r, android.graphics.Path.Direction.CW) }
        c.save(); c.clipPath(clip)
        val tint = when {
            dark && panel -> Color.argb(a(0.34f), 8, 12, 30)
            dark -> Color.argb(a(0.10f), 8, 12, 30)
            panel -> Color.argb(a(0.46f), 250, 252, 255)
            else -> Color.argb(a(0.30f), 250, 252, 255)
        }
        p.shader = null; p.color = tint; c.drawRect(rect, p)
        p.color = Color.BLACK   // opaque again: from here on the shaders carry all the alpha
        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(),
            if (dark) intArrayOf(white(0.13f), white(0.045f), white(0.03f)) else intArrayOf(white(0.40f), white(0.22f), white(0.16f)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(rect, p)
        // top-left sheen, like light pooling in the glass
        val rad = maxOf(w, h) * 0.85f
        p.shader = android.graphics.RadialGradient(w * 0.16f, -h * 0.25f, rad,
            intArrayOf(white(if (dark) 0.13f else 0.32f), white(if (dark) 0.04f else 0.10f), Color.TRANSPARENT),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(rect, p)
        // frosted grain
        p.shader = android.graphics.BitmapShader(grain(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        p.alpha = a(if (dark) 0.035f else 0.045f)
        c.drawRect(rect, p)
        p.alpha = 255
        // specular band along the top edge
        val inset = dp(1.2f)
        val spec = RectF(inset, inset, w - inset, minOf(h * 0.5f, dp(70f)))
        p.shader = LinearGradient(0f, spec.top, 0f, spec.bottom,
            intArrayOf(white(if (dark) 0.10f else 0.34f), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        c.drawRoundRect(RectF(inset, inset, w - inset, h - inset), r - inset, r - inset, p)
        // depth: a faint shade gathering at the bottom
        p.shader = LinearGradient(0f, h * 0.62f, 0f, h.toFloat(),
            intArrayOf(Color.TRANSPARENT, if (dark) Color.argb(a(0.10f), 0, 0, 0) else Color.argb(a(0.035f), 20, 30, 60)),
            null, Shader.TileMode.CLAMP)
        c.drawRect(rect, p)
        p.shader = null
        c.restore()

        // 3. Soft inner edge glow (the thick, bright lip of real glass)
        val glowW = dp(2.2f)
        p.style = Paint.Style.STROKE; p.strokeWidth = glowW
        p.maskFilter = BlurMaskFilter(dp(1.6f), BlurMaskFilter.Blur.NORMAL)
        p.shader = LinearGradient(0f, 0f, w * 0.35f, h.toFloat(),
            intArrayOf(white(if (dark) 0.22f else 0.70f), white(if (dark) 0.03f else 0.18f), white(if (dark) 0.08f else 0.35f)),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        val g = glowW / 2 + dp(0.8f)
        c.save(); c.clipPath(clip)
        c.drawRoundRect(RectF(g, g, w - g, h - g), r - g, r - g, p)
        c.restore()
        p.maskFilter = null

        // 4. Refracted hairline rim
        val hw = maxOf(1f, dp(0.9f))
        p.strokeWidth = hw
        p.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(),
            if (dark) intArrayOf(white(0.75f), white(0.22f), white(0.12f), white(0.42f))
            else intArrayOf(white(1f), white(0.70f), white(0.55f), white(0.90f)),
            floatArrayOf(0f, 0.38f, 0.62f, 1f), Shader.TileMode.CLAMP)
        val e = hw / 2
        c.drawRoundRect(RectF(e, e, w - e, h - e), r - e, r - e, p)

        // 5. Outer definition line
        p.shader = null
        p.color = if (dark) Color.argb(a(0.30f), 0, 0, 0) else Color.argb(a(0.10f), 11, 23, 38)
        p.strokeWidth = maxOf(1f, dp(0.6f))
        if (pad > 0) {
            val o = -p.strokeWidth / 2
            c.save(); c.clipOutRectCompat(rect, r)
            c.drawRoundRect(RectF(o, o, w - o, h - o), r - o, r - o, p)
            c.restore()
        }
        return bmp
    }

    private fun Canvas.clipOutRectCompat(rect: RectF, r: Float) {
        val path = android.graphics.Path().apply { addRoundRect(rect, r, r, android.graphics.Path.Direction.CW) }
        if (android.os.Build.VERSION.SDK_INT >= 26) clipOutPath(path)
        else @Suppress("DEPRECATION") clipPath(path, android.graphics.Region.Op.DIFFERENCE)
    }
}

/** iOS-like easing: quick to start, soft to settle. */
object Ease {
    val out = android.view.animation.PathInterpolator(0.2f, 0.8f, 0.2f, 1f)
    val inOut = android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)
}

/**
 * The Hearth focus glow: purple light from the top-left, pink from the bottom-right.
 * The blur is expensive, so each glow size is rendered once into a bitmap and reused;
 * showing/hiding is then just a cheap hardware alpha fade.
 */
/** Shared timing for the focus glow's slow breath, so every glow on screen feels the same. */
object Glow {
    /** Dimmest point of the breath, as a fraction of full glow. */
    const val PULSE_LOW = 0.74f
    /** One half of the breath (bright → soft); a full cycle is twice this. */
    const val PULSE_HALF_MS = 1700L
    /** 0..1 breath for views that draw their own glow (1 = brightest). */
    fun pulseAt(ms: Long): Float = (0.5 + 0.5 * kotlin.math.cos(ms * Math.PI / PULSE_HALF_MS)).toFloat()
}

class GlowView(context: Context, private val radiusDp: Float, private val padDp: Float) : View(context) {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var bmp: android.graphics.Bitmap? = null

    init {
        alpha = 0f
        isFocusable = false
        visibility = INVISIBLE
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) bmp = GlowCache.get(context, w, h, radiusDp, padDp)
    }

    private val dst = android.graphics.Rect()
    /** 0..1: a slow breath that lifts the glow a little brighter and back; it never fades out. */
    private var pulse = 1f
    private var pulseAnim: ValueAnimator? = null

    override fun onDraw(canvas: Canvas) {
        val b = bmp ?: return
        dst.set(0, 0, width, height)
        paint.alpha = (255 * (Glow.PULSE_LOW + (1f - Glow.PULSE_LOW) * pulse)).toInt()
        canvas.drawBitmap(b, null, dst, paint)
    }

    fun show(on: Boolean) {
        animate().cancel()
        if (on) {
            visibility = VISIBLE
            animate().alpha(1f).setDuration(260).setStartDelay(0).setInterpolator(Ease.out).start()
            startPulse()
        } else {
            animate().alpha(0f).setDuration(180).setInterpolator(Ease.out)
                .withEndAction { if (alpha == 0f) { visibility = INVISIBLE; stopPulse() } }.start()
        }
    }

    private fun startPulse() {
        pulseAnim?.cancel()
        pulse = 1f
        pulseAnim = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = Glow.PULSE_HALF_MS
            startDelay = 300
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { pulse = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun stopPulse() { pulseAnim?.cancel(); pulseAnim = null; pulse = 1f }

    override fun onDetachedFromWindow() { stopPulse(); super.onDetachedFromWindow() }
}

object GlowCache {
    private val cache = HashMap<String, android.graphics.Bitmap>()

    fun get(ctx: Context, w: Int, h: Int, radiusDp: Float, padDp: Float): android.graphics.Bitmap {
        // Render at half size; the glow is soft, so upscaling is invisible and saves memory.
        val key = "$w:$h:$radiusDp:$padDp"
        return cache.getOrPut(key) {
            val s = 0.5f
            val bw = maxOf(1, (w * s).toInt()); val bh = maxOf(1, (h * s).toInt())
            val b = android.graphics.Bitmap.createBitmap(bw, bh, android.graphics.Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            c.scale(s, s)
            val pad = ctx.dp(padDp); val rad = ctx.dp(radiusDp); val off = ctx.dp(5f)
            val r = RectF()
            fun p(color: Int, a: Int, blur: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color; alpha = a; maskFilter = BlurMaskFilter(ctx.dp(blur), BlurMaskFilter.Blur.NORMAL)
            }
            // Blur radii + offsets stay under the padding, so the glow fades to nothing before the bitmap edge.
            r.set(pad, pad, w - pad, h - pad); c.drawRoundRect(r, rad, rad, p(Color.rgb(200, 100, 230), 70, padDp * 0.62f))
            r.set(pad - off, pad - off, w - pad - off, h - pad - off); c.drawRoundRect(r, rad, rad, p(Hearth.PURPLE, 170, padDp * 0.42f))
            r.set(pad + off, pad + off, w - pad + off, h - pad + off); c.drawRoundRect(r, rad, rad, p(Hearth.PINK, 160, padDp * 0.45f))
            b
        }
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
    v.animate().cancel()
    v.animate().scaleX(to).scaleY(to).setDuration(if (to > 1f) 260 else 200).setStartDelay(0).setInterpolator(Ease.out).start()
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

/**
 * A soft oval halo that sits behind text over the live video, so it stays readable on any
 * background. Rendered once in software into a small bitmap (TV GPUs draw large soft
 * gradients with low precision, which shows up as hard edges), then scaled up smoothly.
 */
class SoftHalo(context: Context) : View(context) {
    var dark = true
        set(v) { if (field != v) { field = v; bmp = null; invalidate() } }
    var strength = 1f
    private var bmp: android.graphics.Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = android.graphics.Rect()

    init { isFocusable = false }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh); bmp = null
    }

    private fun render(): android.graphics.Bitmap {
        val bw = 160; val bh = maxOf(16, (160f * height / maxOf(1, width)).toInt())
        val b = android.graphics.Bitmap.createBitmap(bw, bh, android.graphics.Bitmap.Config.ARGB_8888)
        val c = if (dark) Color.rgb(4, 6, 16) else Color.WHITE
        val peak = ((if (dark) 125 else 150) * strength).toInt()
        // Smooth, eased falloff that reaches exactly zero before the edge (no visible boundary).
        for (y in 0 until bh) for (x in 0 until bw) {
            val nx = (x + 0.5f) / bw * 2f - 1f; val ny = (y + 0.5f) / bh * 2f - 1f
            val r = kotlin.math.sqrt(nx * nx + ny * ny)
            val t = (1f - r / 0.96f).coerceIn(0f, 1f)
            val a = (peak * t * t * (3f - 2f * t)).toInt()
            b.setPixel(x, y, Color.argb(a, Color.red(c), Color.green(c), Color.blue(c)))
        }
        return b
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        val b = bmp ?: render().also { bmp = it }
        dst.set(0, 0, width, height)
        canvas.drawBitmap(b, null, dst, paint)
    }
}
