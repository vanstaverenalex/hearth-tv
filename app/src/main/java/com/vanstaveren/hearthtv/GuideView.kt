package com.vanstaveren.hearthtv

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
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

/**
 * The Live TV grid, drawn in one pass on a single glass panel.
 * No per-show boxes: shows are separated by hairlines, the show on now is brighter,
 * and one glowing glass highlight glides to whatever is selected. Channel logos on the left.
 */
class GuideView(context: Context) : View(context) {

    var dark = true
        set(v) { field = v; panel.dark = v; panel.rebuild(); invalidate() }
    var onFocus: ((Guide.Channel, Guide.Program?) -> Unit)? = null
    var onWatch: ((Guide.Channel) -> Unit)? = null
    var onExitUp: (() -> Unit)? = null

    private var all: List<Guide.Channel> = emptyList()
    private var programs: Map<String, List<Guide.Program>> = emptyMap()
    private var channels: List<Guide.Channel> = emptyList()
    private var favs: Set<String> = emptySet()

    private var row = 0
    private var cursor = System.currentTimeMillis()
    private var windowStart = floor30(System.currentTimeMillis()).toDouble()  // animated, ms (Double: Float is too coarse for epoch ms)
    private var targetWindow = windowStart
    private var top = 0f                                                       // animated row offset
    private var targetTop = 0f

    private val MIN = 60_000L
    private val SPAN = 150 * MIN
    private val pad = dp(14f)
    private val headerH = dp(30f)
    private val rowH = dp(42f)
    private val logoW = dp(104f)
    private val gap = dp(6f)

    private val panel = GlassDrawable(context, 26f, true)
    private val hair = Paint().apply { strokeWidth = 1f }
    private val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(12.5f) }
    private val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(10f); typeface = Fonts.body(context, 600) }
    private val nowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val focusFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val focusRim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1.2f) }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val fmt = java.text.SimpleDateFormat("h:mm", java.util.Locale.US)

    private val focus = RectF()
    private val focusTarget = RectF()
    private val focusFrom = RectF()
    private var focusAnim: ValueAnimator? = null
    private var scrollAnim: ValueAnimator? = null
    private val tmp = RectF()

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    private fun floor30(t: Long) = t - (t % (30 * 60_000L))

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
        channels.forEach { ch -> ch.logo?.let { Logos.request(context, it + if (dark) ".png" else "-light.png") { invalidate() } } }
        invalidate()
    }

    val isEmpty get() = channels.isEmpty()

    private fun visibleRows() = maxOf(1, ((height - headerH - pad) / rowH).toInt())

    private fun programsFor(ch: Guide.Channel) = programs[ch.id].orEmpty()

    private fun focusedProgram(): Guide.Program? {
        val ch = channels.getOrNull(row) ?: return null
        val list = programsFor(ch)
        return list.firstOrNull { cursor >= it.start && cursor < it.end } ?: list.minByOrNull { Math.abs(it.start - cursor) }
    }

    // ---------------------------------------------------------------- geometry

    private fun tlLeft() = pad + logoW + gap
    private fun tlWidth() = width - tlLeft() - pad
    private fun xFor(t: Long, win: Double = windowStart) = tlLeft() + ((t - win) / SPAN.toDouble()).toFloat() * tlWidth()
    private fun yFor(r: Int, t: Float = top) = headerH + (r - t) * rowH

    private fun cellRect(r: Int, p: Guide.Program, win: Double, topRow: Float, out: RectF) {
        val x0 = maxOf(tlLeft(), xFor(p.start, win))
        val x1 = minOf(tlLeft() + tlWidth(), xFor(p.end, win))
        val y = yFor(r, topRow)
        out.set(x0 + dp(2f), y + dp(4f), x1 - dp(2f), y + rowH - dp(4f))
    }

    private fun ensureVisible(animate: Boolean = true) {
        val vis = visibleRows()
        var t = targetTop
        if (row < t) t = row.toFloat()
        if (row >= t + vis) t = (row - vis + 1).toFloat()
        var w = targetWindow
        while (cursor >= w + SPAN - 25 * MIN) w += 30 * MIN
        val earliest = floor30(System.currentTimeMillis()) - 30 * MIN
        while (cursor < w && w > earliest) w -= 30 * MIN
        if (t != targetTop || w != targetWindow) {
            targetTop = t; targetWindow = w
            if (!animate || width == 0) { top = t; windowStart = w; return }
            val st = top; val sw = windowStart
            scrollAnim?.cancel()
            scrollAnim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 280; interpolator = Ease.out
                addUpdateListener { a -> val f = a.animatedValue as Float; top = st + (t - st) * f; windowStart = sw + (w - sw) * f.toDouble(); invalidate() }
                start()
            }
        }
    }

    private fun moveFocus(animate: Boolean = true) {
        val ch = channels.getOrNull(row) ?: return
        val p = focusedProgram()
        if (p != null) cellRect(row, p, targetWindow, targetTop, focusTarget)
        else focusTarget.set(tlLeft(), yFor(row, targetTop) + dp(4f), tlLeft() + tlWidth(), yFor(row, targetTop) + rowH - dp(4f))
        onFocus?.invoke(ch, p)
        focusAnim?.cancel()
        if (!animate || focus.isEmpty) { focus.set(focusTarget); invalidate(); return }
        focusFrom.set(focus)
        focusAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220; interpolator = Ease.out
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
        panel.setBounds(0, 0, w, h); panel.rebuild()
        ensureVisible(animate = false); moveFocus(animate = false)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate()
    }

    /** Called every minute so the now-line and "on now" shading stay current. */
    fun tick() { invalidate() }

    // ---------------------------------------------------------------- input

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (channels.isEmpty()) return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (row == 0) { onExitUp?.invoke(); return true }
                row--
            }
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

    fun focusedChannel(): Guide.Channel? = channels.getOrNull(row)

    // ---------------------------------------------------------------- drawing

    override fun onDraw(canvas: Canvas) {
        panel.draw(canvas)
        if (channels.isEmpty()) return
        val now = System.currentTimeMillis()
        val ink = if (dark) Color.WHITE else Hearth.INK_DARK
        fun inkA(a: Int) = Color.argb(a, Color.red(ink), Color.green(ink), Color.blue(ink))
        val win = windowStart
        val winEnd = win + SPAN
        val nowX = xFor(now, win)
        val nowVisible = now >= win && now < winEnd

        // Time header
        small.color = inkA(140)
        var t = (win - (win % (30 * MIN))).toLong()
        if (t < win) t += 30 * MIN
        while (t < winEnd) {
            val x = xFor(t, win)
            if (!nowVisible || Math.abs(x + dp(14f) - nowX) > dp(42f)) canvas.drawText(fmt.format(java.util.Date(t)), x + dp(4f), dp(19f), small)
            t += 30 * MIN
        }

        canvas.save()
        canvas.clipRect(0f, headerH, width.toFloat(), height - dp(6f))

        val first = maxOf(0, top.toInt())
        val last = minOf(channels.size - 1, (top + visibleRows() + 1).toInt())
        hair.color = inkA(if (dark) 20 else 34)
        for (r in first..last) {
            val ch = channels[r]
            val y = yFor(r)
            // Row divider
            if (r < channels.size - 1) canvas.drawLine(pad, y + rowH, width - pad, y + rowH, hair)

            // Logo (or name until it loads)
            val logo = ch.logo?.let { Logos.get(it + if (dark) ".png" else "-light.png") }
            if (logo != null) {
                val bh = dp(26f); val bw = minOf(logoW - dp(18f), bh * logo.width / logo.height)
                val cx = pad + logoW / 2
                tmp.set(cx - bw / 2, y + (rowH - bh) / 2, cx + bw / 2, y + (rowH + bh) / 2)
                canvas.drawBitmap(logo, null, tmp, bmpPaint)
            } else {
                title.typeface = Fonts.body(context, 800); title.color = ink; title.textAlign = Paint.Align.CENTER
                canvas.drawText(TextUtils.ellipsize(ch.name, title, logoW - dp(14f), TextUtils.TruncateAt.END).toString(),
                    pad + logoW / 2, y + rowH / 2 - (title.descent() + title.ascent()) / 2, title)
                title.textAlign = Paint.Align.LEFT
            }
            if (ch.id in favs) { fill.color = Hearth.PINK; canvas.drawCircle(pad + dp(4f), y + rowH / 2, dp(2.5f), fill) }

            // Shows
            for (p in programsFor(ch)) {
                if (p.end <= win || p.start >= winEnd) continue
                cellRect(r, p, win, top, tmp)
                if (tmp.width() < dp(8f)) continue
                val airing = now >= p.start && now < p.end
                if (airing && !p.gap) {
                    fill.shader = null
                    fill.color = if (dark) Color.argb(18, 255, 255, 255) else Color.argb(90, 255, 255, 255)
                    canvas.drawRoundRect(tmp, dp(11f), dp(11f), fill)
                }
                if (p.start > win) {
                    val x = xFor(p.start, win)
                    canvas.drawLine(x, y + dp(11f), x, y + rowH - dp(11f), hair)
                }
                title.typeface = Fonts.body(context, if (airing) 700 else 600)
                title.color = when { p.gap -> inkA(95); airing -> ink; else -> inkA(178) }
                val label = if (p.gap) "Live programming" else p.title
                val avail = tmp.width() - dp(22f)
                if (avail > dp(14f)) {
                    canvas.drawText(TextUtils.ellipsize(label, title, avail, TextUtils.TruncateAt.END).toString(),
                        tmp.left + dp(11f), tmp.centerY() - (title.descent() + title.ascent()) / 2, title)
                }
            }
        }

        // Focus: one glowing glass highlight that glides between shows
        if (isFocused && !focus.isEmpty) {
            val rad = dp(11f)
            for (i in 3 downTo 1) {
                val spread = dp(3.5f) * i
                tmp.set(focus.left - spread, focus.top - spread, focus.right + spread, focus.bottom + spread)
                glow.shader = LinearGradient(tmp.left, tmp.top, tmp.right, tmp.bottom,
                    Color.argb(34 / i, 168, 85, 247), Color.argb(34 / i, 244, 114, 182), Shader.TileMode.CLAMP)
                canvas.drawRoundRect(tmp, rad + spread, rad + spread, glow)
            }
            focusFill.shader = LinearGradient(focus.left, 0f, focus.right, 0f,
                Color.argb(if (dark) 120 else 150, 168, 85, 247), Color.argb(if (dark) 120 else 150, 244, 114, 182), Shader.TileMode.CLAMP)
            canvas.drawRoundRect(focus, rad, rad, focusFill)
            focusRim.color = Color.argb(210, 255, 214, 240)
            canvas.drawRoundRect(focus, rad, rad, focusRim)
            val p = focusedProgram()
            if (p != null) {
                title.typeface = Fonts.body(context, 700); title.color = Color.WHITE
                val label = if (p.gap) "Live programming" else p.title
                canvas.drawText(TextUtils.ellipsize(label, title, focus.width() - dp(22f), TextUtils.TruncateAt.END).toString(),
                    focus.left + dp(11f), focus.centerY() - (title.descent() + title.ascent()) / 2, title)
            }
        }
        canvas.restore()

        // Now line
        if (nowVisible) {
            nowPaint.shader = LinearGradient(0f, headerH, 0f, height.toFloat(), Hearth.PINK, Color.argb(30, 244, 114, 182), Shader.TileMode.CLAMP)
            canvas.drawRect(nowX - dp(0.6f), headerH - dp(4f), nowX + dp(0.6f), height - dp(8f), nowPaint)
            nowPaint.shader = null; nowPaint.color = Hearth.PINK
            tmp.set(nowX - dp(18f), dp(6f), nowX + dp(18f), dp(22f))
            canvas.drawRoundRect(tmp, dp(8f), dp(8f), nowPaint)
            small.color = Color.WHITE; small.textAlign = Paint.Align.CENTER
            small.typeface = Fonts.body(context, 800)
            canvas.drawText(fmt.format(java.util.Date(now)), nowX, tmp.centerY() - (small.descent() + small.ascent()) / 2, small)
            small.textAlign = Paint.Align.LEFT; small.typeface = Fonts.body(context, 600)
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
                if (!f.exists() || System.currentTimeMillis() - f.lastModified() > 7 * 24 * 3600_000L) {
                    val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 10000 }
                    if (c.responseCode == 200) c.inputStream.use { i -> f.outputStream().use { i.copyTo(it) } }
                    c.disconnect()
                }
                if (f.exists()) bmp = BitmapFactory.decodeFile(f.absolutePath)?.let { trimmed(it) }
            } catch (_: Exception) {}
            synchronized(mem) { mem[url] = bmp; loading.remove(url) }
            if (bmp != null) main.post(done)
        }
    }

    /** Logos come padded on a 320x160 canvas; crop to the visible part so they size consistently. */
    private fun trimmed(b: Bitmap): Bitmap {
        var l = b.width; var t = b.height; var r = -1; var btm = -1
        for (y in 0 until b.height step 2) for (x in 0 until b.width step 2) {
            if (Color.alpha(b.getPixel(x, y)) > 16) { if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > btm) btm = y }
        }
        if (r < l || btm < t) return b
        return Bitmap.createBitmap(b, l, t, minOf(b.width - l, r - l + 2), minOf(b.height - t, btm - t + 2))
    }
}
