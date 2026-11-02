package com.vanstaveren.hearthtv

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
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.KeyEvent
import android.view.View
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Critically-damped spring. Unlike a fixed-length tween it never restarts from zero velocity,
 * so holding a direction key produces one continuous glide instead of a series of jolts.
 */
class Spring(var x: Double, private val omega: Double) {
    var target = x
    private var v = 0.0
    val moving get() = abs(target - x) > 1e-3 || abs(v) > 1e-3
    fun snap(to: Double) { x = to; target = to; v = 0.0 }
    /** Advance by dt seconds (exact solution of the critically damped ODE, stable at any frame rate). */
    fun step(dt: Double) {
        val d = x - target
        val e = exp(-omega * dt)
        val c = v + omega * d
        x = target + (d + c * dt) * e
        v = (c - omega * (d + c * dt)) * e
        if (abs(x - target) < 1e-4 && abs(v) < 1e-3) { x = target; v = 0.0 }
    }
}

/**
 * The Live TV grid, drawn in one pass (no child views, so scrolling never stutters).
 * Two-line glass cells (title, then time / episode / NEW / LIVE), small evenly-weighted channel logos,
 * a thin progress line on what's airing, and one glowing Hearth highlight. Rows, time window and
 * highlight all ride on springs, so movement is fluid even when a key is held down.
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

    private val MIN = 60_000L
    private val SPAN = 120 * MIN

    // Animated state, all in content units (rows / epoch ms) so the highlight and the grid can never drift apart.
    private val topS = Spring(0.0, 14.0)
    private val winS = Spring(floor30(System.currentTimeMillis()).toDouble(), 14.0)
    private val fRow = Spring(0.0, 17.0)
    private val fStart = Spring(0.0, 17.0)
    private val fEnd = Spring(0.0, 17.0)
    private var lastFrame = 0L

    private val headerH = dp(30f)
    private val rowH = dp(47f)
    private val gap = dp(4f)
    private val logoW = dp(90f)
    private val fadeH = dp(22f)
    private val radius = dp(10f)

    private val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(11.5f) }
    private val sub = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(9f); typeface = Fonts.body(context, 600) }
    private val tag = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(8f); typeface = Fonts.body(context, 800); letterSpacing = 0.08f }
    private val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(9.5f) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val focusFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val focusRim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1f) }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val nowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val fadePaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
    private val fmt = java.text.SimpleDateFormat("h:mm", java.util.Locale.US)
    private val dayFmt = java.text.SimpleDateFormat("EEEE", java.util.Locale.US)

    private val focus = RectF()
    private val tmp = RectF()
    private val cell = RectF()
    private val ellipsized = HashMap<String, String>()

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
        topS.snap(0.0)
        retarget(animate = false)
        channels.forEach { ch -> ch.logo?.let { Logos.request(context, logoUrl(it)) { invalidate() } } }
        invalidate()
    }

    private fun visibleRows() = maxOf(1, ((height - headerH) / rowH).toInt())
    private fun programsFor(ch: Guide.Channel) = programs[ch.id].orEmpty()

    private fun focusedProgram(): Guide.Program? {
        val ch = channels.getOrNull(row) ?: return null
        val list = programsFor(ch)
        return list.firstOrNull { cursor >= it.start && cursor < it.end } ?: list.minByOrNull { abs(it.start - cursor) }
    }

    fun focusedChannel(): Guide.Channel? = channels.getOrNull(row)

    // ---------------------------------------------------------------- geometry

    private fun tlLeft() = logoW
    private fun tlRight() = width - dp(12f)
    private fun tlWidth() = tlRight() - tlLeft()
    private fun xFor(t: Double, win: Double) = tlLeft() + ((t - win) / SPAN).toFloat() * tlWidth()
    private fun yFor(r: Double, top: Double) = headerH + ((r - top) * rowH).toFloat()

    private fun cellRect(r: Int, p: Guide.Program, win: Double, top: Double, out: RectF) {
        val x0 = maxOf(tlLeft(), xFor(p.start.toDouble(), win))
        val x1 = minOf(tlRight(), xFor(p.end.toDouble(), win))
        val y = yFor(r.toDouble(), top)
        out.set(x0 + gap / 2, y + gap / 2, x1 - gap / 2, y + rowH - gap / 2)
    }

    /** Point every spring at the current selection: rows keep one row of breathing room, the time window follows the cursor. */
    private fun retarget(animate: Boolean = true) {
        val vis = visibleRows()
        val maxTop = maxOf(0, channels.size - vis).toDouble()
        var t = topS.target
        val margin = if (vis >= 5) 1 else 0
        if (row < t + margin) t = (row - margin).toDouble()
        if (row > t + vis - 1 - margin) t = (row - vis + 1 + margin).toDouble()
        topS.target = t.coerceIn(0.0, maxTop)

        var w = winS.target.toLong()
        while (cursor >= w + SPAN - 20 * MIN) w += 30 * MIN
        val earliest = floor30(System.currentTimeMillis()) - 30 * MIN
        while (cursor < w + 5 * MIN && w > earliest) w -= 30 * MIN
        winS.target = w.toDouble()

        val p = focusedProgram()
        fRow.target = row.toDouble()
        fStart.target = (p?.start ?: w).toDouble()
        fEnd.target = (p?.end ?: (w + SPAN)).toDouble()

        channels.getOrNull(row)?.let { onFocus?.invoke(it, p) }
        if (!animate || width == 0) {
            for (s in springs) s.snap(s.target)
            invalidate(); return
        }
        kick()
    }

    private val springs get() = arrayOf(topS, winS, fRow, fStart, fEnd)

    private fun kick() {
        if (lastFrame == 0L) lastFrame = SystemClock.uptimeMillis()
        postInvalidateOnAnimation()
    }

    private fun stepSprings() {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0.0 else ((now - lastFrame).coerceIn(0, 50)) / 1000.0
        lastFrame = now
        var moving = false
        for (s in springs) { if (s.moving) { s.step(dt); moving = moving || s.moving } }
        if (moving) postInvalidateOnAnimation() else lastFrame = 0L
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        ellipsized.clear()
        retarget(animate = false)
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
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ->
                row = minOf(channels.size - 1, row + visibleRows() - 1)
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_REWIND ->
                row = maxOf(0, row - visibleRows() + 1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                channels.getOrNull(row)?.let { onWatch?.invoke(it) }; return true
            }
            else -> return super.onKeyDown(keyCode, event)
        }
        retarget()
        return true
    }

    // ---------------------------------------------------------------- drawing

    private fun fit(text: String, paint: TextPaint, w: Float): String {
        val key = "${paint.textSize}|${paint.typeface?.hashCode()}|${(w / 4).toInt()}|$text"
        ellipsized[key]?.let { return it }
        if (ellipsized.size > 600) ellipsized.clear()
        return TextUtils.ellipsize(text, paint, (w / 4).toInt() * 4f, TextUtils.TruncateAt.END).toString().also { ellipsized[key] = it }
    }

    override fun onDraw(canvas: Canvas) {
        stepSprings()
        if (channels.isEmpty()) return
        val now = System.currentTimeMillis()
        val ink = if (dark) Color.WHITE else Hearth.INK_DARK
        fun inkA(a: Int) = Color.argb(a, Color.red(ink), Color.green(ink), Color.blue(ink))
        val win = winS.x
        val top = topS.x
        val winEnd = win + SPAN
        val nowX = xFor(now.toDouble(), win)
        val nowVisible = now >= win && now < winEnd

        // Header: day over the logos, half-hour marks across
        small.typeface = Fonts.body(context, 800); small.color = inkA(150); small.letterSpacing = 0.1f
        val base = dp(18f)
        canvas.drawText(dayFmt.format(java.util.Date(maxOf(now, win.toLong()))).uppercase(), dp(6f), base, small)
        small.typeface = Fonts.body(context, 600); small.letterSpacing = 0f; small.color = inkA(165)
        var t = (win - (win % (30 * MIN))).toLong()
        if (t < win) t += 30 * MIN
        while (t < winEnd) {
            val x = xFor(t.toDouble(), win)
            val near = nowVisible && abs(x + dp(14f) - nowX) < dp(42f)
            if (!near && x + dp(40f) < tlRight()) canvas.drawText(fmt.format(java.util.Date(t)), x + dp(6f), base, small)
            t += 30 * MIN
        }

        // Rows draw into their own layer so the top and bottom edges can fade softly.
        val layerTop = headerH - dp(2f)
        val save = canvas.saveLayer(0f, layerTop, width.toFloat(), height.toFloat(), null)
        val first = maxOf(0, top.toInt())
        val last = minOf(channels.size - 1, (top + visibleRows() + 1).toInt())
        for (r in first..last) {
            val ch = channels[r]
            val y = yFor(r.toDouble(), top)

            // Logo, sized by area so wide and square logos carry the same visual weight
            val logo = ch.logo?.let { Logos.get(logoUrl(it)) }
            val cx = dp(6f) + dp(62f) / 2
            if (logo != null) {
                val ratio = logo.width.toFloat() / logo.height
                var h = sqrt(dp(48f) * dp(16f) / ratio).coerceIn(dp(11f), dp(22f))
                var w = h * ratio
                if (w > dp(62f)) { w = dp(62f); h = w / ratio }
                tmp.set(cx - w / 2, y + (rowH - h) / 2, cx + w / 2, y + (rowH + h) / 2)
                bmpPaint.alpha = if (r == row && isFocused) 255 else 225
                canvas.drawBitmap(logo, null, tmp, bmpPaint)
            } else {
                title.typeface = Fonts.body(context, 800); title.color = inkA(220); title.textAlign = Paint.Align.CENTER
                canvas.drawText(fit(ch.name, title, dp(64f)), cx, y + rowH / 2 - (title.descent() + title.ascent()) / 2, title)
                title.textAlign = Paint.Align.LEFT
            }
            if (ch.id in favs) { fill.shader = null; fill.color = Hearth.PINK; canvas.drawCircle(logoW - dp(9f), y + rowH / 2, dp(2f), fill) }

            val list = programsFor(ch)
            if (list.isEmpty()) {
                cell.set(tlLeft() + gap / 2, y + gap / 2, tlRight() - gap / 2, y + rowH - gap / 2)
                fill.shader = null; fill.color = if (dark) Color.argb(12, 255, 255, 255) else Color.argb(80, 255, 255, 255)
                canvas.drawRoundRect(cell, radius, radius, fill)
            }
            for (p in list) {
                if (p.end <= win || p.start >= winEnd) continue
                cellRect(r, p, win, top, cell)
                if (cell.width() < dp(6f)) continue
                val airing = now >= p.start && now < p.end
                fill.shader = null
                fill.color = when {
                    p.gap -> if (dark) Color.argb(10, 255, 255, 255) else Color.argb(70, 255, 255, 255)
                    airing -> if (dark) Color.argb(30, 255, 255, 255) else Color.argb(185, 255, 255, 255)
                    else -> if (dark) Color.argb(17, 255, 255, 255) else Color.argb(120, 255, 255, 255)
                }
                canvas.drawRoundRect(cell, radius, radius, fill)
                drawCell(canvas, p, cell, now, airing, false, ink, ::inkA)
            }
        }

        // Focus: one glowing glass highlight riding the same springs as the grid
        if (isFocused) {
            val y = yFor(fRow.x, top)
            val x0 = maxOf(tlLeft(), xFor(fStart.x, win)) + gap / 2
            val x1 = minOf(tlRight(), xFor(fEnd.x, win)) - gap / 2
            focus.set(x0, y + gap / 2, maxOf(x0 + dp(24f), x1), y + rowH - gap / 2)
            for (i in 3 downTo 1) {
                val spread = dp(3.5f) * i
                tmp.set(focus.left - spread, focus.top - spread, focus.right + spread, focus.bottom + spread)
                glow.shader = LinearGradient(tmp.left, tmp.top, tmp.right, tmp.bottom,
                    Color.argb(34 / i, 168, 85, 247), Color.argb(34 / i, 244, 114, 182), Shader.TileMode.CLAMP)
                canvas.drawRoundRect(tmp, radius + spread, radius + spread, glow)
            }
            focusFill.shader = LinearGradient(focus.left, focus.top, focus.right, focus.bottom,
                Color.argb(215, 147, 72, 232), Color.argb(215, 230, 98, 172), Shader.TileMode.CLAMP)
            canvas.drawRoundRect(focus, radius, radius, focusFill)
            // glass sheen on the upper half
            fill.shader = LinearGradient(0f, focus.top, 0f, focus.centerY(), Color.argb(46, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
            canvas.drawRoundRect(focus, radius, radius, fill); fill.shader = null
            focusRim.shader = LinearGradient(0f, focus.top, 0f, focus.bottom, Color.argb(200, 255, 228, 246), Color.argb(60, 255, 228, 246), Shader.TileMode.CLAMP)
            canvas.drawRoundRect(focus, radius, radius, focusRim)
            focusedProgram()?.let { p ->
                canvas.save(); canvas.clipRect(focus)
                drawCell(canvas, p, focus, now, now >= p.start && now < p.end, true, ink) { a -> Color.argb(a, 255, 255, 255) }
                canvas.restore()
            }
        }

        // Soft fades at the top and bottom of the rows
        if (top > 0.01) {
            fadePaint.shader = LinearGradient(0f, layerTop, 0f, layerTop + fadeH, Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, layerTop, width.toFloat(), layerTop + fadeH, fadePaint)
        }
        if (top + visibleRows() < channels.size - 0.01) {
            fadePaint.shader = LinearGradient(0f, height - fadeH, 0f, height.toFloat(), Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, height - fadeH, width.toFloat(), height.toFloat(), fadePaint)
        }
        canvas.restoreToCount(save)

        // Now line + time pill
        if (nowVisible) {
            nowPaint.shader = LinearGradient(0f, headerH, 0f, height.toFloat(), Color.argb(230, 244, 114, 182), Color.argb(0, 244, 114, 182), Shader.TileMode.CLAMP)
            canvas.drawRect(nowX - dp(0.6f), headerH - dp(4f), nowX + dp(0.6f), height.toFloat(), nowPaint)
            nowPaint.shader = LinearGradient(nowX - dp(20f), 0f, nowX + dp(20f), 0f, Hearth.PURPLE, Hearth.PINK, Shader.TileMode.CLAMP)
            tmp.set(nowX - dp(20f), dp(7f), nowX + dp(20f), dp(23f))
            canvas.drawRoundRect(tmp, dp(8f), dp(8f), nowPaint)
            small.color = Color.WHITE; small.textAlign = Paint.Align.CENTER; small.typeface = Fonts.body(context, 800)
            canvas.drawText(fmt.format(java.util.Date(now)), nowX, tmp.centerY() - (small.descent() + small.ascent()) / 2, small)
            small.textAlign = Paint.Align.LEFT; small.typeface = Fonts.body(context, 600)
        }

        // Slim scroll indicator that glides with the rows
        val vis = visibleRows()
        if (channels.size > vis) {
            val trackTop = headerH + dp(6f); val trackBottom = height - dp(6f)
            val x = width - dp(4f)
            fill.shader = null; fill.color = inkA(22)
            tmp.set(x - dp(1.5f), trackTop, x + dp(1.5f), trackBottom); canvas.drawRoundRect(tmp, dp(1.5f), dp(1.5f), fill)
            val span = trackBottom - trackTop
            val thumbH = maxOf(dp(20f), span * vis / channels.size)
            val pos = (top / maxOf(1.0, (channels.size - vis).toDouble())).toFloat().coerceIn(0f, 1f)
            val ty = trackTop + (span - thumbH) * pos
            fill.color = inkA(130)
            tmp.set(x - dp(1.5f), ty, x + dp(1.5f), ty + thumbH); canvas.drawRoundRect(tmp, dp(1.5f), dp(1.5f), fill)
        }
    }

    /** Title on top; underneath a quiet line: LIVE / NEW in Hearth pink, then time left or start time, then the episode. */
    private fun drawCell(canvas: Canvas, p: Guide.Program, r: RectF, now: Long, airing: Boolean, focused: Boolean, ink: Int, inkA: (Int) -> Int) {
        val padL = dp(11f)
        val x = r.left + padL
        val avail = r.right - dp(9f) - x
        if (avail < dp(14f)) return
        val twoLines = r.width() > dp(70f) && !p.gap
        title.typeface = Fonts.body(context, if (airing || focused) 700 else 600)
        title.color = when { focused -> Color.WHITE; p.gap -> inkA(110); airing -> ink; else -> inkA(200) }
        val label = if (p.gap) "Live programming" else p.title
        if (!twoLines) {
            canvas.drawText(fit(label, title, avail), x, r.centerY() - (title.descent() + title.ascent()) / 2, title)
            return
        }
        val titleBase = r.centerY() - dp(1.5f)
        canvas.drawText(fit(label, title, avail), x, titleBase, title)

        val subBase = r.centerY() + dp(11.5f)
        var sx = x
        val pink = if (focused) Color.WHITE else if (dark) 0xFFFF9FD0.toInt() else 0xFFC0267A.toInt()
        val mark = when { p.isLive && airing -> "LIVE"; p.isNew -> "NEW"; else -> null }
        if (mark != null) {
            tag.color = pink
            if (mark == "LIVE") { fill.shader = null; fill.color = pink; canvas.drawCircle(sx + dp(2.5f), subBase - dp(3f), dp(2.5f), fill); sx += dp(8f) }
            canvas.drawText(mark, sx, subBase, tag)
            sx += tag.measureText(mark) + dp(7f)
        }
        val time = if (airing) "${maxOf(1, (p.end - now) / 60_000)} min left" else fmt.format(java.util.Date(p.start))
        val line = listOf(time, p.detail).filter { it.isNotBlank() }.joinToString("  ·  ")
        sub.color = if (focused) Color.argb(225, 255, 255, 255) else inkA(if (dark) 150 else 165)
        val room = r.right - dp(9f) - sx
        if (room > dp(14f)) canvas.drawText(fit(line, sub, room), sx, subBase, sub)

        // Thin progress line on what's airing now
        if (airing && !focused && r.width() > dp(40f)) {
            val f = ((now - p.start).toFloat() / (p.end - p.start)).coerceIn(0f, 1f)
            val l = r.left + dp(10f); val rr = r.right - dp(10f)
            fill.shader = null; fill.color = inkA(if (dark) 26 else 40)
            tmp.set(l, r.bottom - dp(4f), rr, r.bottom - dp(2.5f)); canvas.drawRoundRect(tmp, dp(1f), dp(1f), fill)
            fill.shader = LinearGradient(l, 0f, rr, 0f, Hearth.PURPLE, Hearth.PINK, Shader.TileMode.CLAMP)
            tmp.set(l, r.bottom - dp(4f), l + (rr - l) * f, r.bottom - dp(2.5f)); canvas.drawRoundRect(tmp, dp(1f), dp(1f), fill)
            fill.shader = null
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
