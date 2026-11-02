package com.vanstaveren.hearthtv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Path
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.LruCache
import android.text.Layout
import android.text.StaticLayout
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
 * Two-line glass cells, small evenly-weighted channel logos and a thin progress line on what's airing.
 * The selected channel's row opens into a glowing card: the show's poster (or the channel's logo when
 * a listing has none), title, episode, a two-line description and what's on next. Scroll, time window
 * and the opening row all ride on springs, so movement stays fluid even with a key held down.
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

    /**
     * Sibling view (same bounds, added right after this one) that draws the card's soft halo with a
     * slow breath. Kept separate so the glow can animate without redrawing the whole grid.
     */
    val rim = RimOverlay(context)

    private var all: List<Guide.Channel> = emptyList()
    private var programs: Map<String, List<Guide.Program>> = emptyMap()
    private var channels: List<Guide.Channel> = emptyList()
    private var favs: Set<String> = emptySet()

    private var row = 0
    private var cursor = System.currentTimeMillis()

    private val MIN = 60_000L
    private val SPAN = 120 * MIN

    private val headerH = dp(30f)
    private val rowH = dp(47f)
    private val expH = dp(150f)          // height of the opened row
    private val extra = expH - rowH
    private val gap = dp(4f)
    private val logoW = dp(90f)
    private val fadeH = dp(22f)
    private val radius = dp(10f)
    private val cardR = dp(16f)

    // Animated state: scroll in px, time window in epoch ms, the opened row as a (fractional) row index.
    private val scrollS = Spring(0.0, 14.0)
    private val winS = Spring(floor30(System.currentTimeMillis()).toDouble(), 14.0)
    private val fRow = Spring(0.0, 16.0)
    private val springs = arrayOf(scrollS, winS, fRow)
    private var lastFrame = 0L

    private val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(11.5f) }
    private val sub = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(9f); typeface = Fonts.body(context, 600) }
    private val tag = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(8f); typeface = Fonts.body(context, 800); letterSpacing = 0.08f }
    private val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(9.5f) }
    private val cardTitle = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(20f); typeface = Fonts.display(context, 400); color = Color.WHITE }
    private val cardMeta = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(10.5f); typeface = Fonts.body(context, 700); color = Color.WHITE }
    private val cardBody = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(10f); typeface = Fonts.body(context, 500); color = Color.argb(225, 255, 255, 255) }
    private val cardCaps = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(8.5f); typeface = Fonts.body(context, 800); letterSpacing = 0.09f; color = Color.argb(220, 255, 255, 255) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val focusFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val focusRim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1f) }
    private val nowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val fadePaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
    private val fmt = java.text.SimpleDateFormat("h:mm", java.util.Locale.US)
    private val dayFmt = java.text.SimpleDateFormat("EEEE", java.util.Locale.US)

    private val card = RectF()
    private val tmp = RectF()
    private val cell = RectF()
    private val clip = Path()
    private val ellipsized = HashMap<String, String>()
    private var descLayout: StaticLayout? = null
    private var descKey = ""

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    private fun floor30(t: Long) = t - (t % (30 * 60_000L))
    private fun logoUrl(base: String) = base + (if (dark) ".png" else "-light.png") + "?v=${Guide.logoVersion}"
    /** The white logo always: it sits on the coloured card. */
    private fun cardLogoUrl(base: String) = "$base.png?v=${Guide.logoVersion}"

    // ---------------------------------------------------------------- data

    fun setData(lineup: Guide.Lineup, favorites: Set<String>, category: String) {
        all = lineup.channels; programs = lineup.programs; favs = favorites
        setCategory(category, keepPosition = true)
    }

    fun setFavorites(f: Set<String>) { favs = f; invalidate() }

    /** Jump to what's on right now, at the top of the list (used every time the guide opens). */
    fun resetToNow() {
        cursor = System.currentTimeMillis()
        row = 0
        scrollS.snap(0.0)
        winS.snap(floor30(cursor).toDouble())
        retarget(animate = false)
    }

    fun setCategory(cat: String, keepPosition: Boolean = false) {
        val keepId = if (keepPosition) channels.getOrNull(row)?.id else null
        channels = when (cat) {
            "all" -> all.filter { it.id in favs } + all.filter { it.id !in favs }
            "favorites" -> all.filter { it.id in favs }
            else -> all.filter { it.category == cat }
        }
        row = keepId?.let { id -> channels.indexOfFirst { it.id == id } }?.takeIf { it >= 0 } ?: 0
        if (!keepPosition) scrollS.snap(0.0)
        retarget(animate = false)
        channels.forEach { ch -> ch.logo?.let { Logos.request(context, logoUrl(it)) { invalidate() }; Logos.request(context, cardLogoUrl(it)) { invalidate() } } }
        invalidate()
    }

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
    private fun visH() = height - headerH

    /** Content y of row r while the open row sits at fractional index f (rows after it are pushed down). */
    private fun rowY(r: Int, f: Double) = (r * rowH + extra * (r - f).coerceIn(0.0, 1.0)).toFloat()
    private fun screenY(r: Int) = headerH + rowY(r, fRow.x) - scrollS.x.toFloat()
    private fun slotH(r: Int) = rowH + extra * (1 - abs(r - fRow.x)).coerceIn(0.0, 1.0).toFloat()
    private fun contentH() = channels.size * rowH + extra
    private fun maxScroll() = maxOf(0f, contentH() - visH() + dp(6f))

    private fun cellRect(left: Long, right: Long, y: Float, win: Double, out: RectF) {
        val x0 = maxOf(tlLeft(), xFor(left.toDouble(), win))
        val x1 = minOf(tlRight(), xFor(right.toDouble(), win))
        out.set(x0 + gap / 2, y + gap / 2, x1 - gap / 2, y + rowH - gap / 2)
    }

    /** Point every spring at the current selection: the open row keeps breathing room, the time window follows the cursor. */
    private fun retarget(animate: Boolean = true) {
        val y = row * rowH
        var s = scrollS.target.toFloat()
        val margin = rowH * 0.7f
        if (y - margin < s) s = y - margin
        if (y + expH + margin > s + visH()) s = y + expH + margin - visH()
        scrollS.target = s.coerceIn(0f, maxScroll()).toDouble()

        var w = winS.target.toLong()
        while (cursor >= w + SPAN - 20 * MIN) w += 30 * MIN
        val earliest = floor30(System.currentTimeMillis()) - 30 * MIN
        while (cursor < w + 5 * MIN && w > earliest) w -= 30 * MIN
        winS.target = w.toDouble()
        fRow.target = row.toDouble()

        val p = focusedProgram()
        p?.art?.let { Art.request(context, it) { invalidate() } }
        channels.getOrNull(row)?.let { onFocus?.invoke(it, p) }
        if (!animate || width == 0) { for (sp in springs) sp.snap(sp.target); invalidate(); return }
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
        ellipsized.clear(); descLayout = null
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
        val page = maxOf(1, (visH() / rowH).toInt() - 2)
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
                row = minOf(channels.size - 1, row + page)
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_REWIND ->
                row = maxOf(0, row - page)
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

        // Rows go into their own layer so the top and bottom edges can fade softly.
        val layerTop = headerH - dp(2f)
        val save = canvas.saveLayer(0f, layerTop, width.toFloat(), height.toFloat(), null)
        for (r in channels.indices) {
            val y = screenY(r)
            val h = slotH(r)
            if (y > height || y + h < layerTop) continue
            val ch = channels[r]
            val openness = (1 - abs(r - fRow.x)).coerceIn(0.0, 1.0).toFloat()

            // Logo, sized by area so wide and square logos carry the same visual weight
            val logo = ch.logo?.let { Logos.get(logoUrl(it)) }
            val cx = dp(6f) + dp(62f) / 2
            val cy = y + h / 2
            if (logo != null) {
                val ratio = logo.width.toFloat() / logo.height
                var lh = sqrt(dp(48f) * dp(16f) / ratio).coerceIn(dp(11f), dp(22f)) * (1f + 0.15f * openness)
                var lw = lh * ratio
                if (lw > dp(66f)) { lw = dp(66f); lh = lw / ratio }
                tmp.set(cx - lw / 2, cy - lh / 2, cx + lw / 2, cy + lh / 2)
                bmpPaint.alpha = if (isFocused && r == row) 255 else 225
                canvas.drawBitmap(logo, null, tmp, bmpPaint)
            } else {
                title.typeface = Fonts.body(context, 800); title.color = inkA(220); title.textAlign = Paint.Align.CENTER
                canvas.drawText(fit(ch.name, title, dp(64f)), cx, cy - (title.descent() + title.ascent()) / 2, title)
                title.textAlign = Paint.Align.LEFT
            }
            if (ch.id in favs) { fill.shader = null; fill.color = Hearth.PINK; canvas.drawCircle(logoW - dp(9f), cy, dp(2f), fill) }

            // The opened row is covered by its card; skip its cells so nothing shows through the glass.
            if (openness > 0.5f) continue
            val cellTop = y + (h - rowH) / 2
            val list = programsFor(ch)
            if (list.isEmpty()) {
                cell.set(tlLeft() + gap / 2, cellTop + gap / 2, tlRight() - gap / 2, cellTop + rowH - gap / 2)
                fill.shader = null; fill.color = if (dark) Color.argb(12, 255, 255, 255) else Color.argb(80, 255, 255, 255)
                canvas.drawRoundRect(cell, radius, radius, fill)
            }
            for (p in list) {
                if (p.end <= win || p.start >= winEnd) continue
                cellRect(p.start, p.end, cellTop, win, cell)
                if (cell.width() < dp(6f)) continue
                val airing = now >= p.start && now < p.end
                fill.shader = null
                fill.color = when {
                    p.gap -> if (dark) Color.argb(10, 255, 255, 255) else Color.argb(70, 255, 255, 255)
                    airing -> if (dark) Color.argb(30, 255, 255, 255) else Color.argb(185, 255, 255, 255)
                    else -> if (dark) Color.argb(17, 255, 255, 255) else Color.argb(120, 255, 255, 255)
                }
                canvas.drawRoundRect(cell, radius, radius, fill)
                drawCell(canvas, p, cell, now, airing, ink, ::inkA)
            }
        }

        // Soft fades at the top and bottom of the rows
        val scroll = scrollS.x.toFloat()
        if (scroll > 1f) {
            fadePaint.shader = LinearGradient(0f, layerTop, 0f, layerTop + fadeH, Color.BLACK, Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, layerTop, width.toFloat(), layerTop + fadeH, fadePaint)
        }
        if (scroll < maxScroll() - 1f) {
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

        // The opened row: one glowing card that glides with the selection
        drawCard(canvas, now)

        // Slim scroll indicator that glides with the rows
        if (maxScroll() > 1f) {
            val trackTop = headerH + dp(6f); val trackBottom = height - dp(6f)
            val x = width - dp(4f)
            fill.shader = null; fill.color = inkA(22)
            tmp.set(x - dp(1.5f), trackTop, x + dp(1.5f), trackBottom); canvas.drawRoundRect(tmp, dp(1.5f), dp(1.5f), fill)
            val span = trackBottom - trackTop
            val thumbH = maxOf(dp(20f), span * visH() / contentH())
            val ty = trackTop + (span - thumbH) * (scroll / maxScroll()).coerceIn(0f, 1f)
            fill.color = inkA(130)
            tmp.set(x - dp(1.5f), ty, x + dp(1.5f), ty + thumbH); canvas.drawRoundRect(tmp, dp(1.5f), dp(1.5f), fill)
        }
    }

    private fun drawCard(canvas: Canvas, now: Long) {
        val ch = channels.getOrNull(row) ?: return
        val p = focusedProgram()
        val top = headerH + (fRow.x * rowH).toFloat() - scrollS.x.toFloat()
        card.set(tlLeft() + gap / 2, top + gap / 2, tlRight() - gap / 2, top + expH - gap / 2)
        // The halo around the card is drawn by [rim] (its own layer) so it can breathe without redrawing the grid.
        focusFill.shader = LinearGradient(card.left, card.top, card.right, card.bottom,
            Color.argb(235, 147, 72, 232), Color.argb(235, 230, 98, 172), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(card, cardR, cardR, focusFill)
        fill.shader = LinearGradient(0f, card.top, 0f, card.top + expH * 0.5f, Color.argb(46, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(card, cardR, cardR, fill); fill.shader = null
        focusRim.shader = LinearGradient(0f, card.top, 0f, card.bottom, Color.argb(200, 255, 228, 246), Color.argb(60, 255, 228, 246), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(card, cardR, cardR, focusRim)

        rim.show(card, cardR, isFocused)

        // Content fades in as the card settles on its row, and eases in again when the show changes.
        val key = "$row|${p?.start}|${ch.id}"
        val t = SystemClock.uptimeMillis()
        if (key != contentKey) { contentKey = key; contentAt = t }
        val k = ((t - contentAt) / 220f).coerceIn(0f, 1f)
        val ease = 1f - (1f - k) * (1f - k) * (1f - k)
        if (k < 1f) postInvalidateOnAnimation()
        val settle = (1 - abs(fRow.x - row) * 2.5).toFloat().coerceIn(0f, 1f)
        val alpha = (255 * settle * ease).toInt().coerceIn(0, 255)
        if (alpha == 0) return
        val layer = if (alpha < 255) canvas.saveLayerAlpha(card, alpha) else canvas.save()
        canvas.clipRect(card)
        canvas.translate((1f - ease) * dp(10f), 0f)

        val pad = dp(11f)
        val ph = card.height() - 2 * pad
        val pw = ph * 0.75f
        tmp.set(card.left + pad, card.top + pad, card.left + pad + pw, card.bottom - pad)
        drawPoster(canvas, ch, p, tmp, now)

        val upW = if (card.width() > dp(640f)) dp(190f) else 0f
        val x0 = tmp.right + dp(16f)
        val x1 = card.right - pad - (if (upW > 0) upW + dp(18f) else 0f)
        val tw = x1 - x0
        var y = card.top + pad + dp(10f)

        val airing = p != null && now >= p.start && now < p.end
        val range = if (p != null) "${fmt.format(java.util.Date(p.start))} – ${fmt.format(java.util.Date(p.end))}" else ""
        val caps = when {
            p == null || p.gap -> "ON NOW"
            p.isLive && airing -> "LIVE · $range"
            airing -> "ON NOW · $range"
            p.isNew -> "NEW · $range"
            else -> range
        }
        canvas.drawText(fit(caps, cardCaps, tw), x0, y, cardCaps)
        y += dp(22f)
        val name = if (p == null || p.gap) ch.name else p.title
        canvas.drawText(fit(name, cardTitle, tw), x0, y, cardTitle)
        y += dp(17f)
        val left = if (airing && p != null) "${maxOf(1, (p.end - now) / 60_000)} min left" else ""
        val meta = listOf(p?.detail.orEmpty(), left).filter { it.isNotBlank() }.joinToString("  ·  ")
        if (meta.isNotBlank()) { canvas.drawText(fit(meta, cardMeta, tw), x0, y, cardMeta); y += dp(8f) }
        val desc = when { p == null || p.gap -> "Live programming · press OK to watch on Hulu"; p.desc.isNotBlank() -> p.desc; else -> "" }
        if (desc.isNotBlank() && tw > dp(60f)) {
            val key = "$desc|${tw.toInt()}"
            if (key != descKey || descLayout == null) {
                descKey = key
                descLayout = StaticLayout.Builder.obtain(desc, 0, desc.length, cardBody, tw.toInt())
                    .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0f, 1.12f)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
            }
            canvas.save(); canvas.translate(x0, y + dp(2f)); descLayout?.draw(canvas); canvas.restore()
        }

        // Up next on this channel
        if (upW > 0f) {
            val ux = card.right - pad - upW
            fill.shader = null; fill.color = Color.argb(70, 255, 255, 255)
            canvas.drawRect(ux - dp(18f), card.top + pad + dp(2f), ux - dp(17f), card.bottom - pad - dp(2f), fill)
            var uy = card.top + pad + dp(10f)
            canvas.drawText("UP NEXT", ux, uy, cardCaps)
            val after = programsFor(ch).filter { !it.gap && it.start >= (p?.end ?: now) }.take(3)
            cardMeta.typeface = Fonts.body(context, 600)
            for (n in after) {
                uy += dp(19f)
                val tm = fmt.format(java.util.Date(n.start))
                cardMeta.color = Color.argb(205, 255, 255, 255)
                canvas.drawText(tm, ux, uy, cardMeta)
                cardMeta.color = Color.WHITE
                canvas.drawText(fit(n.title, cardMeta, upW - dp(40f)), ux + dp(38f), uy, cardMeta)
            }
            cardMeta.typeface = Fonts.body(context, 700); cardMeta.color = Color.WHITE
            if (after.isEmpty()) { cardMeta.color = Color.argb(205, 255, 255, 255); canvas.drawText("More soon", ux, uy + dp(19f), cardMeta); cardMeta.color = Color.WHITE }
        }
        canvas.restoreToCount(layer)
    }

    /** The show's poster, or (no artwork / still loading) the channel's logo on soft glass. */
    private fun drawPoster(canvas: Canvas, ch: Guide.Channel, p: Guide.Program?, r: RectF, now: Long) {
        val pr = dp(11f)
        val art = p?.art?.let { Art.get(it) }
        canvas.save()
        clip.reset(); clip.addRoundRect(r, pr, pr, Path.Direction.CW); canvas.clipPath(clip)
        if (art != null) {
            // The channel logo shows until the poster arrives, then the poster fades in over it.
            val seen = artSeen.getOrPut(p!!.art!!) { if (artSeen.size > 300) artSeen.clear(); SystemClock.uptimeMillis() }
            val fadeIn = ((SystemClock.uptimeMillis() - seen) / 260f).coerceIn(0f, 1f)
            if (fadeIn < 1f) { drawPosterFallback(canvas, ch, r); postInvalidateOnAnimation() }
            // centre-crop into the 3:4 frame
            val s = maxOf(r.width() / art.width, r.height() / art.height)
            val w = art.width * s; val h = art.height * s
            tmp2.set(r.centerX() - w / 2, r.centerY() - h / 2, r.centerX() + w / 2, r.centerY() + h / 2)
            bmpPaint.alpha = (255 * fadeIn).toInt()
            canvas.drawBitmap(art, null, tmp2, bmpPaint)
        } else drawPosterFallback(canvas, ch, r)
        // progress along the bottom of the poster for what's airing
        if (p != null && !p.gap && now >= p.start && now < p.end) {
            val f = ((now - p.start).toFloat() / (p.end - p.start)).coerceIn(0f, 1f)
            fill.shader = null; fill.color = Color.argb(90, 0, 0, 0)
            canvas.drawRect(r.left, r.bottom - dp(3f), r.right, r.bottom, fill)
            fill.color = Color.WHITE
            canvas.drawRect(r.left, r.bottom - dp(3f), r.left + r.width() * f, r.bottom, fill)
        }
        canvas.restore()
        fill.shader = null; fill.style = Paint.Style.STROKE; fill.strokeWidth = dp(0.8f); fill.color = Color.argb(70, 255, 255, 255)
        canvas.drawRoundRect(r, pr, pr, fill)
        fill.style = Paint.Style.FILL
    }

    private fun drawPosterFallback(canvas: Canvas, ch: Guide.Channel, r: RectF) {
        run {
            fill.shader = LinearGradient(r.left, r.top, r.right, r.bottom, Color.argb(90, 20, 10, 50), Color.argb(60, 20, 10, 50), Shader.TileMode.CLAMP)
            canvas.drawRect(r, fill); fill.shader = null
            val logo = ch.logo?.let { Logos.get(cardLogoUrl(it)) }
            if (logo != null) {
                val ratio = logo.width.toFloat() / logo.height
                var lw = r.width() * 0.68f; var lh = lw / ratio
                if (lh > r.height() * 0.3f) { lh = r.height() * 0.3f; lw = lh * ratio }
                tmp2.set(r.centerX() - lw / 2, r.centerY() - lh / 2, r.centerX() + lw / 2, r.centerY() + lh / 2)
                bmpPaint.alpha = 240
                canvas.drawBitmap(logo, null, tmp2, bmpPaint)
            } else {
                cardMeta.textAlign = Paint.Align.CENTER
                canvas.drawText(fit(ch.name, cardMeta, r.width() - dp(10f)), r.centerX(), r.centerY() + dp(4f), cardMeta)
                cardMeta.textAlign = Paint.Align.LEFT
            }
        }
    }
    private val tmp2 = RectF()
    private var contentKey = ""
    private var contentAt = 0L
    private val artSeen = HashMap<String, Long>()

    /** Title on top; underneath a quiet line: LIVE / NEW in Hearth pink, then time left or start time, then the episode. */
    private fun drawCell(canvas: Canvas, p: Guide.Program, r: RectF, now: Long, airing: Boolean, ink: Int, inkA: (Int) -> Int) {
        val padL = dp(11f)
        val x = r.left + padL
        val avail = r.right - dp(9f) - x
        if (avail < dp(14f)) return
        val twoLines = r.width() > dp(70f) && !p.gap
        title.typeface = Fonts.body(context, if (airing) 700 else 600)
        title.color = when { p.gap -> inkA(110); airing -> ink; else -> inkA(200) }
        val label = if (p.gap) "Live programming" else p.title
        if (!twoLines) {
            canvas.drawText(fit(label, title, avail), x, r.centerY() - (title.descent() + title.ascent()) / 2, title)
            return
        }
        canvas.drawText(fit(label, title, avail), x, r.centerY() - dp(1.5f), title)
        val subBase = r.centerY() + dp(11.5f)
        var sx = x
        val pink = if (dark) 0xFFFF9FD0.toInt() else 0xFFC0267A.toInt()
        val mark = when { p.isLive && airing -> "LIVE"; p.isNew -> "NEW"; else -> null }
        if (mark != null) {
            tag.color = pink
            if (mark == "LIVE") { fill.shader = null; fill.color = pink; canvas.drawCircle(sx + dp(2.5f), subBase - dp(3f), dp(2.5f), fill); sx += dp(8f) }
            canvas.drawText(mark, sx, subBase, tag)
            sx += tag.measureText(mark) + dp(7f)
        }
        val time = if (airing) "${maxOf(1, (p.end - now) / 60_000)} min left" else fmt.format(java.util.Date(p.start))
        val line = listOf(time, p.detail).filter { it.isNotBlank() }.joinToString("  ·  ")
        sub.color = inkA(if (dark) 150 else 165)
        val room = r.right - dp(9f) - sx
        if (room > dp(14f)) canvas.drawText(fit(line, sub, room), sx, subBase, sub)
        if (airing && r.width() > dp(40f)) {
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

/** The guide card's halo: Hearth-coloured glow outside the card's edge, breathing slowly brighter and back. */
class RimOverlay(context: Context) : View(context) {
    private val rect = RectF()
    private var radius = 0f
    private var active = false
    private var since = 0L
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()
    private val tmp = RectF()

    init { isFocusable = false; isClickable = false }

    fun show(r: RectF, rad: Float, on: Boolean) {
        if (on && !active) since = SystemClock.uptimeMillis()
        val changed = on != active || r != rect
        active = on; rect.set(r); radius = rad
        if (changed) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (!active || rect.isEmpty) return
        val t = SystemClock.uptimeMillis() - since
        val appear = (t / 260f).coerceIn(0f, 1f).let { 1f - (1f - it) * (1f - it) * (1f - it) }
        val k = appear * (Glow.PULSE_LOW + (1f - Glow.PULSE_LOW) * Glow.pulseAt(t))
        canvas.save()
        clip.reset(); clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
        @Suppress("DEPRECATION")
        canvas.clipPath(clip, android.graphics.Region.Op.DIFFERENCE)   // glow only outside the card
        for (i in 4 downTo 1) {
            val spread = dp(4.5f) * i
            tmp.set(rect.left - spread, rect.top - spread, rect.right + spread, rect.bottom + spread)
            val a = (46f / i * k).toInt()
            glow.shader = LinearGradient(tmp.left, tmp.top, tmp.right, tmp.bottom,
                Color.argb(a, 168, 85, 247), Color.argb(a, 244, 114, 182), Shader.TileMode.CLAMP)
            canvas.drawRoundRect(tmp, radius + spread, radius + spread, glow)
        }
        canvas.restore()
        postInvalidateOnAnimation()
    }
}

/** Show posters for the guide's opened row: Gracenote artwork, cached on disk and in a small memory cache. */
object Art {
    private val mem = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val missing = HashSet<String>()
    private val loading = HashSet<String>()
    private val io = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    fun get(id: String): Bitmap? = mem.get(id)

    fun request(ctx: Context, id: String, done: () -> Unit) {
        synchronized(loading) { if (mem.get(id) != null || id in missing || !loading.add(id)) return }
        io.execute {
            val dir = File(ctx.cacheDir, "art").apply { mkdirs() }
            val f = File(dir, "$id.jpg")
            var bmp: Bitmap? = null
            try {
                if (!f.exists()) {
                    val c = (URL("https://zpmc.tmsimg.com/assets/$id.jpg?w=240").openConnection() as HttpURLConnection).apply {
                        connectTimeout = 8000; readTimeout = 12000
                        setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11; AFT) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Safari/537.36")
                    }
                    if (c.responseCode == 200) c.inputStream.use { i -> f.outputStream().use { it.write(i.readBytes()) } }
                    c.disconnect()
                    // keep the disk cache small
                    dir.listFiles()?.takeIf { it.size > 400 }?.sortedBy { it.lastModified() }?.take(100)?.forEach { it.delete() }
                }
                if (f.exists()) bmp = BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })
            } catch (_: Exception) {}
            synchronized(loading) { loading.remove(id); if (bmp == null) missing.add(id) }
            if (bmp != null) { mem.put(id, bmp); main.post(done) }
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
