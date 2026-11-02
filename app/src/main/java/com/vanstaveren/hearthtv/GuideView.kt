package com.vanstaveren.hearthtv

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.text.TextUtils
import android.view.KeyEvent
import android.view.View
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.sqrt

/**
 * The Live TV grid, drawn in one pass (no child views, so scrolling never stutters).
 * Rows of soft translucent cells over the live background, small evenly-sized channel logos,
 * NEW / LIVE tags, one glowing Hearth highlight that glides between shows,
 * soft fades at the top and bottom edges and a slim scroll indicator.
 */
class GuideView(context: Context) : View(context) {

    var dark = true
        set(v) {
            field = v
            channels.forEach { ch -> ch.logo?.let { Logos.request(context, logoUrl(it)) { invalidate() } } }
            invalidate()
        }
    var onFocus: ((Guide.Channel, Guide.Program?) -> Unit)? = null
    var onWatch: ((Guide.Channel) -> Unit)? = null
    var onExitUp: (() -> Unit)? = null

    private var all: List<Guide.Channel> = emptyList()
    private var programs: Map<String, List<Guide.Program>> = emptyMap()
    private var channels: List<Guide.Channel> = emptyList()
    private var favs: Set<String> = emptySet()

    private var row = 0
    private var cursor = System.currentTimeMillis()
    private var windowStart = floor30(System.currentTimeMillis()).toDouble()  // animated; Double keeps ms precision
    private var targetWindow = windowStart
    private var top = 0f
    private var targetTop = 0f

    private val MIN = 60_000L
    private val SPAN = 120 * MIN
    private val headerH = dp(28f)
    private val rowH = dp(38f)
    private val gap = dp(3f)
    private val logoW = dp(86f)
    private val fadeH = dp(18f)
    private val radius = dp(8f)

    private val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(11.5f) }
    private val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(9.5f) }
    private val badge = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(7f); typeface = Fonts.body(context, 800); letterSpacing = 0.06f }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val focusFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val focusRim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1.2f) }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val nowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val fadePaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
    private val fmt = java.text.SimpleDateFormat("h:mm", java.util.Locale.US)
    private val dayFmt = java.text.SimpleDateFormat("EEEE", java.util.Locale.US)

    private val focus = RectF()
    private val focusTarget = RectF()
    private val focusFrom = RectF()
    private var focusAnim: ValueAnimator? = null
    private var scrollAnim: ValueAnimator? = null
    private val tmp = RectF()
    private val cell = RectF()

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    private fun floor30(t: Long) = t - (t % (30 * 60_000L))
    private fun logoUrl(base: String) = base + if (dark) ".png" else "-light.png"

    // ---------------------------------------------------------------- data

    fun setData(lineup: Guide.Lineup, favorites: Set<String>, category: String) {
        all = lineup.channels; programs = lineup.programs; favs = favorites
        setCategory(category, keepPosition = true)
    }

    fun setFavorites(f: Set<String>) { favs = f; invalidate() }

    fun setCategory(cat: String, keepPosition: Boolean = false) {
        val keepId = if (keepPosition) channels.getOrNull(row)?.id else null
        channels = when (cat) {
            "all" -> all.filter { it.id in favs } + all.filter { it.id !in favs }
            "favorites" -> all.filter { it.id in favs }
            else -> all.filter { it.category == cat }
        }
        row = keepId?.let { id -> channels.indexOfFirst { it.id == id } }?.takeIf { it >= 0 } ?: 0
        top = 0f; targetTop = 0f
        ensureVisible(animate = false)
        moveFocus(animate = false)
        channels.forEach { ch -> ch.logo?.let { Logos.request(context, logoUrl(it)) { invalidate() } } }
        invalidate()
    }

    private fun visibleRows() = maxOf(1, ((height - headerH) / rowH).toInt())
    private fun programsFor(ch: Guide.Channel) = programs[ch.id].orEmpty()

    private fun focusedProgram(): Guide.Program? {
        val ch = channels.getOrNull(row) ?: return null
        val list = programsFor(ch)
        return list.firstOrNull { cursor >= it.start && cursor < it.end } ?: list.minByOrNull { Math.abs(it.start - cursor) }
    }

    fun focusedChannel(): Guide.Channel? = channels.getOrNull(row)

    // ---------------------------------------------------------------- geometry

    private fun tlLeft() = logoW
    private fun tlWidth() = width - tlLeft() - dp(12f)
    private fun xFor(t: Long, win: Double = windowStart) = tlLeft() + ((t - win) / SPAN.toDouble()).toFloat() * tlWidth()
    private fun yFor(r: Int, t: Float = top) = headerH + (r - t) * rowH

    private fun cellRect(r: Int, p: Guide.Program, win: Double, topRow: Float, out: RectF) {
        val x0 = maxOf(tlLeft(), xFor(p.start, win))
        val x1 = minOf(tlLeft() + tlWidth(), xFor(p.end, win))
        val y = yFor(r, topRow)
        out.set(x0 + gap / 2, y + gap / 2, x1 - gap / 2, y + rowH - gap / 2)
    }

    /** Scroll so the selection stays one row away from the edges, and the time window follows the cursor. */
    private fun ensureVisible(animate: Boolean = true) {
        val vis = visibleRows()
        val maxTop = maxOf(0, channels.size - vis).toFloat()
        var t = targetTop
        val margin = if (vis >= 5) 1 else 0
        if (row < t + margin) t = (row - margin).toFloat()
        if (row > t + vis - 1 - margin) t = (row - vis + 1 + margin).toFloat()
        t = t.coerceIn(0f, maxTop)
        var w = targetWindow
        while (cursor >= w + SPAN - 20 * MIN) w += 30 * MIN
        val earliest = floor30(System.currentTimeMillis()) - 30 * MIN
        while (cursor < w + 5 * MIN && w > earliest) w -= 30 * MIN
        if (t == targetTop && w == targetWindow) return
        targetTop = t; targetWindow = w
        if (!animate || width == 0) { top = t; windowStart = w; return }
        val st = top; val sw = windowStart
        scrollAnim?.cancel()
        scrollAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320; interpolator = Ease.out
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                top = st + (t - st) * f; windowStart = sw + (w - sw) * f.toDouble(); invalidate()
            }
            start()
        }
    }

    private fun moveFocus(animate: Boolean = true) {
        val ch = channels.getOrNull(row) ?: return
        val p = focusedProgram()
        if (p != null) cellRect(row, p, targetWindow, targetTop, focusTarget)
        else focusTarget.set(tlLeft(), yFor(row, targetTop) + gap / 2, tlLeft() + tlWidth(), yFor(row, targetTop) + rowH - gap / 2)
        onFocus?.invoke(ch, p)
        focusAnim?.cancel()
        if (!animate || focus.isEmpty) { focus.set(focusTarget); invalidate(); return }
        focusFrom.set(focus)
        focusAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 240; interpolator = Ease.out
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                focus.set(
                    focusFrom.left + (focusTarget.left - focusFrom.left) * f,
                    focusFrom.top + (focusTarget.top - focusFrom.top) * f,
                    focusFrom.right + (focusTarget.right - focusFrom.right) * f,
                    focusFrom.bottom + (focusTarget.bottom - focusFrom.bottom) * f,
                )
                invalidate()
            }
            start()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        ensureVisible(animate = false); moveFocus(animate = false)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate()
    }

    fun tick() { invalidate() }

    // ---------------------------------------------------------------- input

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (channels.isEmpty()) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> { if (row == 0) { onExitUp?.invoke(); return true }; row-- }
            KeyEvent.KEYCODE_DPAD_DOWN -> if (row < channels.size - 1) row++ else return true
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val list = programsFor(channels[row]); val cur = focusedProgram()
                val next = list.firstOrNull { cur != null && it.start >= cur.end } ?: return true
                cursor = next.start + MIN
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                val earliest = floor30(System.currentTimeMillis()) - 30 * MIN
                val list = programsFor(channels[row]); val cur = focusedProgram()
                val prev = list.lastOrNull { cur != null && it.end <= cur.start && it.end > earliest } ?: return true
                cursor = maxOf(prev.start, earliest) + MIN
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                channels.getOrNull(row)?.let { onWatch?.invoke(it) }; return true
            }
            else -> return super.onKeyDown(keyCode, event)
        }
        ensureVisible(); moveFocus()
        return true
    }

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        if (channels.isEmpty()) return
        val now = System.currentTimeMillis()
        val ink = if (dark) Color.WHITE else Hearth.INK_DARK
        fun inkA(a: Int) = Color.argb(a, Color.red(ink), Color.green(ink), Color.blue(ink))
        val win = windowStart
        val winEnd = win + SPAN
        val nowX = xFor(now, win)
        val nowVisible = now >= win && now < winEnd

        // Header: day on the left, half-hour marks across
        small.typeface = Fonts.body(context, 800); small.color = inkA(150); small.letterSpacing = 0.08f
        canvas.drawText(dayFmt.format(java.util.Date(maxOf(now, win.toLong()))).uppercase(), dp(4f), dp(17f), small)
        small.typeface = Fonts.body(context, 600); small.letterSpacing = 0f
        var t = (win - (win % (30 * MIN))).toLong()
        if (t < win) t += 30 * MIN
        while (t < winEnd) {
            val x = xFor(t, win)
            if (!nowVisible || Math.abs(x + dp(14f) - nowX) > dp(40f)) canvas.drawText(fmt.format(java.util.Date(t)), x + dp(6f), dp(17f), small)
            t += 30 * MIN
        }

        // Rows go into their own layer so the top and bottom edges can fade softly.
        val save = canvas.saveLayer(0f, headerH - dp(2f), width.toFloat(), height.toFloat(), null)
        val first = maxOf(0, top.toInt())
        val last = minOf(channels.size - 1, (top + visibleRows() + 1).toInt())
        for (r in first..last) {
            val ch = channels[r]
            val y = yFor(r)

            // Logo, sized by area so wide and square logos look equally weighted
            val logo = ch.logo?.let { Logos.get(logoUrl(it)) }
            if (logo != null) {
                val ratio = logo.width.toFloat() / logo.height
                var h = sqrt(dp(46f) * dp(15f) / ratio).coerceIn(dp(10f), dp(20f))
                var w = h * ratio
                if (w > dp(60f)) { w = dp(60f); h = w / ratio }
                val cx = dp(4f) + dp(60f) / 2
                tmp.set(cx - w / 2, y + (rowH - h) / 2, cx + w / 2, y + (rowH + h) / 2)
                canvas.drawBitmap(logo, null, tmp, bmpPaint)
            } else {
                title.typeface = Fonts.body(context, 800); title.color = ink
                canvas.drawText(TextUtils.ellipsize(ch.name, title, logoW - dp(14f), TextUtils.TruncateAt.END).toString(),
                    dp(4f), y + rowH / 2 - (title.descent() + title.ascent()) / 2, title)
            }
            if (ch.id in favs) { fill.shader = null; fill.color = Hearth.PINK; canvas.drawCircle(logoW - dp(12f), y + rowH / 2, dp(2.2f), fill) }

            // Show cells
            val list = programsFor(ch)
            if (list.isEmpty()) {
                cell.set(tlLeft() + gap / 2, y + gap / 2, tlLeft() + tlWidth() - gap / 2, y + rowH - gap / 2)
                fill.shader = null; fill.color = if (dark) Color.argb(14, 255, 255, 255) else Color.argb(90, 255, 255, 255)
                canvas.drawRoundRect(cell, radius, radius, fill)
            }
            for (p in list) {
                if (p.end <= win || p.start >= winEnd) continue
                cellRect(r, p, win, top, cell)
                if (cell.width() < dp(6f)) continue
                val airing = now >= p.start && now < p.end
                fill.shader = null
                fill.color = when {
                    p.gap -> if (dark) Color.argb(12, 255, 255, 255) else Color.argb(80, 255, 255, 255)
                    airing -> if (dark) Color.argb(34, 255, 255, 255) else Color.argb(190, 255, 255, 255)
                    else -> if (dark) Color.argb(20, 255, 255, 255) else Color.argb(125, 255, 255, 255)
                }
                canvas.drawRoundRect(cell, radius, radius, fill)
                drawCellText(canvas, p, cell, airing, false, ink, ::inkA)
            }
        }

        // Focus: one glowing glass highlight that glides between shows
        if (isFocused && !focus.isEmpty) {
            for (i in 3 downTo 1) {
                val spread = dp(3f) * i
                tmp.set(focus.left - spread, focus.top - spread, focus.right + spread, focus.bottom + spread)
                glow.shader = LinearGradient(tmp.left, tmp.top, tmp.right, tmp.bottom,
                    Color.argb(30 / i, 168, 85, 247), Color.argb(30 / i, 244, 114, 182), Shader.TileMode.CLAMP)
                canvas.drawRoundRect(tmp, radius + spread, radius + spread, glow)
            }
            focusFill.shader = LinearGradient(focus.left, 0f, focus.right, 0f,
                Color.argb(200, 150, 70, 235), Color.argb(200, 232, 96, 170), Shader.TileMode.CLAMP)
            canvas.drawRoundRect(focus, radius, radius, focusFill)
            focusRim.color = Color.argb(210, 255, 214, 240)
            canvas.drawRoundRect(focus, radius, radius, focusRim)
            focusedProgram()?.let { drawCellText(canvas, it, focus, now >= it.start && now < it.end, true, ink) { a -> Color.argb(a, 255, 255, 255) } }
        }

        // Soft fades at the top and bottom of the rows
        fadePaint.shader = LinearGradient(0f, headerH - dp(2f), 0f, headerH - dp(2f) + fadeH, Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        if (top > 0.01f) canvas.drawRect(0f, headerH - dp(2f), width.toFloat(), headerH - dp(2f) + fadeH, fadePaint)
        fadePaint.shader = LinearGradient(0f, height - fadeH, 0f, height.toFloat(), Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
        if (top + visibleRows() < channels.size - 0.01f) canvas.drawRect(0f, height - fadeH, width.toFloat(), height.toFloat(), fadePaint)
        canvas.restoreToCount(save)

        // Now line
        if (nowVisible) {
            nowPaint.shader = LinearGradient(0f, headerH, 0f, height.toFloat(), Hearth.PINK, Color.argb(20, 244, 114, 182), Shader.TileMode.CLAMP)
            canvas.drawRect(nowX - dp(0.6f), headerH - dp(4f), nowX + dp(0.6f), height.toFloat(), nowPaint)
            nowPaint.shader = null; nowPaint.color = Hearth.PINK
            tmp.set(nowX - dp(18f), dp(6f), nowX + dp(18f), dp(22f))
            canvas.drawRoundRect(tmp, dp(8f), dp(8f), nowPaint)
            small.color = Color.WHITE; small.textAlign = Paint.Align.CENTER; small.typeface = Fonts.body(context, 800)
            canvas.drawText(fmt.format(java.util.Date(now)), nowX, tmp.centerY() - (small.descent() + small.ascent()) / 2, small)
            small.textAlign = Paint.Align.LEFT; small.typeface = Fonts.body(context, 600)
        }

        // Slim scroll indicator
        val vis = visibleRows()
        if (channels.size > vis) {
            val trackTop = headerH + dp(4f); val trackBottom = height - dp(4f)
            val x = width - dp(4f)
            fill.shader = null; fill.color = inkA(26)
            tmp.set(x - dp(1.5f), trackTop, x + dp(1.5f), trackBottom); canvas.drawRoundRect(tmp, dp(1.5f), dp(1.5f), fill)
            val span = trackBottom - trackTop
            val thumbH = maxOf(dp(18f), span * vis / channels.size)
            val pos = (top / maxOf(1f, (channels.size - vis).toFloat())).coerceIn(0f, 1f)
            val ty = trackTop + (span - thumbH) * pos
            fill.color = inkA(140)
            tmp.set(x - dp(1.5f), ty, x + dp(1.5f), ty + thumbH); canvas.drawRoundRect(tmp, dp(1.5f), dp(1.5f), fill)
        }
    }

    private fun drawCellText(canvas: Canvas, p: Guide.Program, r: RectF, airing: Boolean, focused: Boolean, ink: Int, inkA: (Int) -> Int) {
        val padL = dp(10f)
        var x = r.left + padL
        val cy = r.centerY()
        val avail = r.right - dp(8f)
        // Tag first: LIVE (filled) or NEW (outlined)
        val tag = when { p.gap -> null; p.isLive && airing -> "LIVE"; p.isNew -> "NEW"; else -> null }
        if (tag != null && avail - x > dp(70f)) {
            val tw = badge.measureText(tag) + dp(8f)
            tmp.set(x, cy - dp(6.5f), x + tw, cy + dp(6.5f))
            if (tag == "LIVE") {
                fill.shader = null; fill.color = if (focused) Color.WHITE else Hearth.PINK
                canvas.drawRoundRect(tmp, dp(6.5f), dp(6.5f), fill)
                badge.color = if (focused) 0xFFB0448F.toInt() else Color.WHITE
            } else {
                fill.shader = null; fill.color = if (focused) Color.argb(70, 255, 255, 255) else Color.argb(if (dark) 46 else 70, 244, 114, 182)
                canvas.drawRoundRect(tmp, dp(6.5f), dp(6.5f), fill)
                badge.color = if (focused) Color.WHITE else if (dark) 0xFFFFB3DA.toInt() else 0xFFB0307A.toInt()
            }
            canvas.drawText(tag, tmp.left + dp(4f), cy - (badge.descent() + badge.ascent()) / 2, badge)
            x = tmp.right + dp(6f)
        }
        title.typeface = Fonts.body(context, if (airing || focused) 700 else 600)
        title.color = when { focused -> Color.WHITE; p.gap -> inkA(105); airing -> ink; else -> inkA(190) }
        val label = if (p.gap) "Live programming" else p.title
        if (avail - x > dp(12f)) {
            canvas.drawText(TextUtils.ellipsize(label, title, avail - x, TextUtils.TruncateAt.END).toString(),
                x, cy - (title.descent() + title.ascent()) / 2, title)
        }
    }
}

/** Downloads and caches channel logos (small PNGs) for the guide. */
object Logos {
    private val mem = HashMap<String, Bitmap?>()
    private val loading = HashSet<String>()
    private val io = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    fun get(url: String): Bitmap? = synchronized(mem) { mem[url] }

    fun request(ctx: Context, url: String, done: () -> Unit) {
        synchronized(mem) { if (mem.containsKey(url) || !loading.add(url)) return }
        io.execute {
            val f = File(File(ctx.cacheDir, "logos").apply { mkdirs() }, url.substringAfterLast('/'))
            var bmp: Bitmap? = null
            try {
                if (!f.exists() || System.currentTimeMillis() - f.lastModified() > 3 * 24 * 3600_000L) {
                    val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 10000 }
                    if (c.responseCode == 200) c.inputStream.use { i -> f.outputStream().use { it.write(i.readBytes()) } }
                    c.disconnect()
                }
                if (f.exists()) bmp = BitmapFactory.decodeFile(f.absolutePath)?.let { trimmed(it) }
            } catch (_: Exception) {}
            synchronized(mem) { mem[url] = bmp; loading.remove(url) }
            if (bmp != null) main.post(done)
        }
    }

    /** Logos arrive padded on a 320x160 canvas; crop to the visible part so sizing is consistent. */
    private fun trimmed(b: Bitmap): Bitmap {
        var l = b.width; var t = b.height; var r = -1; var btm = -1
        for (y in 0 until b.height step 2) for (x in 0 until b.width step 2) {
            if (Color.alpha(b.getPixel(x, y)) > 16) { if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > btm) btm = y }
        }
        if (r < l || btm < t) return b
        return Bitmap.createBitmap(b, l, t, minOf(b.width - l, r - l + 2), minOf(b.height - t, btm - t + 2))
    }
}
