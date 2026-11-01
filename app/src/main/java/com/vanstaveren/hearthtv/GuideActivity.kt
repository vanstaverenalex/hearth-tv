package com.vanstaveren.hearthtv

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The Live TV guide: a calm glass grid of the family's Hulu Live channels.
 * D-pad moves through shows, OK opens that channel in Hulu, ☰ pins a channel to the top.
 */
class GuideActivity : Activity() {

    companion object {
        const val EXTRA_DARK = "dark"
        private const val SPAN_MIN = 150          // minutes visible across the grid
        private const val ROWS = 8
        private const val ROW_H = 35f
        private const val ROW_GAP = 5f
        private const val CHIP_W = 98f
        private const val GRID_W = 820f
        private val TL_W = GRID_W - CHIP_W - 8f    // timeline width in dp
        private const val MIN = 60_000L
    }

    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var dark = true

    private var lineup: Guide.Lineup? = null
    private var channels: List<Guide.Channel> = emptyList()
    private var row = 0
    private var topRow = 0
    private var cursor = System.currentTimeMillis()
    private var windowStart = floor30(System.currentTimeMillis())

    private lateinit var root: FrameLayout
    private lateinit var heroPill: TextView
    private lateinit var heroMeta: TextView
    private lateinit var heroTitle: TextView
    private lateinit var heroDetail: TextView
    private lateinit var heroHint: TextView
    private lateinit var ruler: FrameLayout
    private lateinit var grid: FrameLayout
    private lateinit var status: TextView

    private val ink get() = if (dark) Color.WHITE else Hearth.INK_DARK
    private fun inkA(a: Int) = Color.argb(a, Color.red(ink), Color.green(ink), Color.blue(ink))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dark = intent.getBooleanExtra(EXTRA_DARK, true)
        build()
        setContentView(root)
        load()
    }

    override fun onResume() {
        super.onResume()
        tick()
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacksAndMessages(null)
    }

    private fun floor30(t: Long) = t - (t % (30 * MIN))

    // ------------------------------------------------------------------ layout

    private fun build() {
        root = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                if (dark) intArrayOf(0xFF0A1030.toInt(), 0xFF1C1D42.toInt(), 0xFF3A2C52.toInt(), 0xFF2A2246.toInt(), 0xFF0E1230.toInt())
                else intArrayOf(0xFFDCEBF8.toInt(), 0xFFEEF3FA.toInt(), 0xFFF7EEF2.toInt(), 0xFFEFF2F8.toInt(), 0xFFE2ECF6.toInt())
            )
        }
        // Soft Hearth-colored light blooms behind the glass.
        root.addView(blob(if (dark) 0x40F472B6 else 0x33F472B6), FrameLayout.LayoutParams(dpi(420f), dpi(260f)).apply {
            leftMargin = dpi(520f); topMargin = dpi(250f)
        })
        root.addView(blob(if (dark) 0x33A855F7 else 0x26A855F7), FrameLayout.LayoutParams(dpi(380f), dpi(240f)).apply {
            leftMargin = dpi(-80f); topMargin = dpi(-60f)
        })

        // Hero
        val hero = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pillRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        heroPill = TextView(this).apply {
            style(8f, Fonts.body(this@GuideActivity, 800), Color.WHITE, 0.08f)
            gravity = Gravity.CENTER; setPadding(dpi(7f), 0, dpi(7f), 0)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFFFB7185.toInt(), Hearth.PINK)).apply { cornerRadius = dp(9f) }
        }
        heroMeta = TextView(this).apply { style(11f, Fonts.body(this@GuideActivity, 600), inkA(205)) }
        pillRow.addView(heroPill, LinearLayout.LayoutParams(-2, dpi(17f)).apply { rightMargin = dpi(8f) })
        pillRow.addView(heroMeta)
        heroTitle = TextView(this).apply {
            style(32f, Fonts.display(this@GuideActivity, 400), ink, -0.02f)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        heroDetail = TextView(this).apply {
            style(12f, Fonts.body(this@GuideActivity, 500), inkA(190))
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        heroHint = TextView(this).apply {
            text = "OK  Watch on Hulu      ☰  Favorite channel"
            style(10f, Fonts.body(this@GuideActivity, 700), inkA(170))
        }
        hero.addView(pillRow)
        hero.addView(heroTitle, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(7f) })
        hero.addView(heroDetail, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(5f) })
        hero.addView(heroHint, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(10f) })
        root.addView(hero, FrameLayout.LayoutParams(dpi(600f), -2).apply { leftMargin = dpi(70f); topMargin = dpi(30f) })

        // Brand
        val brand = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val mark = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Hearth.PURPLE, Hearth.PINK)).apply { cornerRadius = dp(9f) }
        }
        mark.addView(ImageView(this).apply { setImageResource(R.drawable.ic_network) }, FrameLayout.LayoutParams(dpi(14f), dpi(14f), Gravity.CENTER))
        brand.addView(mark, LinearLayout.LayoutParams(dpi(26f), dpi(26f)).apply { rightMargin = dpi(7f) })
        val bt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        bt.addView(TextView(this).apply { text = "Live TV"; style(14f, Fonts.display(this@GuideActivity, 600), ink) })
        bt.addView(TextView(this).apply { text = "HEARTH"; style(7f, Fonts.body(this@GuideActivity, 700), inkA(190), 0.08f); setPadding(0, dpi(2f), 0, 0) })
        brand.addView(bt)
        root.addView(brand, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { rightMargin = dpi(70f); topMargin = dpi(36f) })

        // Ruler + grid
        ruler = FrameLayout(this)
        root.addView(ruler, FrameLayout.LayoutParams(dpi(TL_W), dpi(20f)).apply {
            leftMargin = dpi(70f + CHIP_W + 8f); topMargin = dpi(145f)
        })
        grid = FrameLayout(this).apply { clipChildren = false }
        root.addView(grid, FrameLayout.LayoutParams(dpi(GRID_W), dpi(ROWS * (ROW_H + ROW_GAP))).apply {
            leftMargin = dpi(70f); topMargin = dpi(170f)
        })

        status = TextView(this).apply { text = "Loading listings…"; style(14f, Fonts.body(this@GuideActivity, 600), inkA(200)); gravity = Gravity.CENTER }
        root.addView(status, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply { topMargin = dpi(60f) })

        root.addView(TextView(this).apply { text = "Listings from TVmaze · games from ESPN"; style(7f, Fonts.body(this@GuideActivity, 600), inkA(115)) },
            FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { rightMargin = dpi(70f); bottomMargin = dpi(11f) })
    }

    private fun blob(color: Int) = View(this).apply {
        background = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = dp(200f)
            colors = intArrayOf(color, Color.TRANSPARENT)
        }
    }

    // ------------------------------------------------------------------ data

    private fun load() {
        worker.execute {
            val l = try { Guide.load(this) } catch (e: Exception) { null }
            ui.post {
                if (l == null || l.channels.isEmpty()) {
                    status.text = "Couldn't load the channel list. Check the internet connection."
                    return@post
                }
                lineup = l
                orderChannels()
                status.visibility = View.GONE
                render()
            }
        }
    }

    private fun favChannels(): MutableList<String> =
        getSharedPreferences("hearth", Context.MODE_PRIVATE).getString("fav_channels", "")!!
            .split(",").filter { it.isNotBlank() }.toMutableList()

    private fun orderChannels() {
        val all = lineup?.channels ?: return
        val favs = favChannels()
        val keepId = channels.getOrNull(row)?.id
        channels = favs.mapNotNull { f -> all.firstOrNull { it.id == f } } + all.filter { it.id !in favs }
        if (keepId != null) row = channels.indexOfFirst { it.id == keepId }.coerceAtLeast(0)
    }

    private fun programsFor(ch: Guide.Channel) = lineup?.programs?.get(ch.id).orEmpty()

    private fun focusedProgram(): Guide.Program? {
        val ch = channels.getOrNull(row) ?: return null
        val list = programsFor(ch)
        return list.firstOrNull { cursor >= it.start && cursor < it.end } ?: list.minByOrNull { Math.abs(it.start - cursor) }
    }

    // ------------------------------------------------------------------ render

    private val timeFmt = SimpleDateFormat("h:mm", Locale.US)
    private val timeFmtA = SimpleDateFormat("h:mm a", Locale.US)

    private fun xFor(t: Long): Float = ((t - windowStart).toFloat() / (SPAN_MIN * MIN)) * TL_W

    private fun render() {
        val now = System.currentTimeMillis()
        val windowEnd = windowStart + SPAN_MIN * MIN
        if (row < topRow) topRow = row
        if (row >= topRow + ROWS) topRow = row - ROWS + 1

        // Ruler
        ruler.removeAllViews()
        val nowX = xFor(now)
        var t = windowStart
        while (t < windowEnd) {
            val x = xFor(t)
            if (now !in windowStart until windowEnd || Math.abs(x + 14f - nowX) > 45f) {
                ruler.addView(TextView(this).apply { text = timeFmt.format(Date(t)); style(10f, Fonts.body(this@GuideActivity, 600), inkA(150)) },
                    FrameLayout.LayoutParams(-2, -2).apply { leftMargin = dpi(x + 2f); topMargin = dpi(3f) })
            }
            t += 30 * MIN
        }
        if (now in windowStart until windowEnd) {
            ruler.addView(TextView(this).apply {
                text = timeFmt.format(Date(now)); gravity = Gravity.CENTER
                style(8.5f, Fonts.body(this@GuideActivity, 800), Color.WHITE)
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFFFB7185.toInt(), Hearth.PINK)).apply { cornerRadius = dp(8f) }
            }, FrameLayout.LayoutParams(dpi(36f), dpi(15f)).apply { leftMargin = dpi(nowX - 18f); topMargin = dpi(1f) })
        }

        // Rows
        grid.removeAllViews()
        val favs = favChannels()
        val focused = focusedProgram()
        for (i in 0 until ROWS) {
            val ci = topRow + i
            val ch = channels.getOrNull(ci) ?: break
            val y = i * (ROW_H + ROW_GAP)

            val chip = FrameLayout(this).apply { background = GlassDrawable(this@GuideActivity, 12f, dark) }
            chip.addView(TextView(this).apply {
                text = if (ch.id in favs) "★ ${ch.name}" else ch.name
                gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                style(if (ch.name.length > 8) 10.5f else 13f, Fonts.body(this@GuideActivity, 800), ink)
                setPadding(dpi(6f), 0, dpi(6f), 0)
            }, FrameLayout.LayoutParams(-1, -1))
            grid.addView(chip, FrameLayout.LayoutParams(dpi(CHIP_W), dpi(ROW_H)).apply { topMargin = dpi(y) })

            for (p in programsFor(ch)) {
                if (p.end <= windowStart || p.start >= windowEnd) continue
                val x0 = maxOf(0f, xFor(p.start))
                val x1 = minOf(TL_W, xFor(p.end))
                val w = x1 - x0 - 5f
                if (w < 6f) continue
                val isFocus = ci == row && p == focused
                val airing = now >= p.start && now < p.end
                val block = FrameLayout(this).apply {
                    background = when {
                        isFocus -> LayerDrawable(arrayOf(
                            hearthGradient(this@GuideActivity, 12f, if (dark) 0.42f else 0.55f),
                            rimDrawable(this@GuideActivity, 12f, true, dark)
                        ))
                        else -> GradientDrawable().apply {
                            cornerRadius = dp(12f)
                            val a = when { p.gap -> 8; airing -> 28; else -> 12 }
                            setColor(if (dark) Color.argb(a, 255, 255, 255) else Color.argb(a * 4, 255, 255, 255))
                            if (airing && !p.gap) setStroke(dpi(0.6f), if (dark) Color.argb(46, 255, 255, 255) else Color.argb(160, 255, 255, 255))
                        }
                    }
                }
                val line = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(dpi(11f), 0, dpi(10f), 0)
                }
                line.addView(TextView(this).apply {
                    text = p.title; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                    val c = when { isFocus -> Color.WHITE; p.gap -> inkA(125); airing -> ink; else -> inkA(185) }
                    style(11.5f, Fonts.body(this@GuideActivity, if (isFocus || airing) 700 else 600), c)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                if (!p.gap && w > 120f && p.start >= windowStart) {
                    line.addView(TextView(this).apply {
                        text = timeFmt.format(Date(p.start))
                        style(9f, Fonts.body(this@GuideActivity, 600), if (isFocus) Color.argb(220, 255, 255, 255) else inkA(120))
                    })
                }
                block.addView(line, FrameLayout.LayoutParams(-1, -1))
                grid.addView(block, FrameLayout.LayoutParams(dpi(w), dpi(ROW_H)).apply {
                    leftMargin = dpi(CHIP_W + 8f + x0); topMargin = dpi(y)
                })
            }
        }
        if (now in windowStart until windowEnd) {
            grid.addView(View(this).apply {
                background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Hearth.PINK, 0x26F472B6))
            }, FrameLayout.LayoutParams(dpi(1.2f), -1).apply { leftMargin = dpi(CHIP_W + 8f + nowX) })
        }

        renderHero(focused)
    }

    private fun renderHero(p: Guide.Program?) {
        val ch = channels.getOrNull(row)
        if (p == null || ch == null) return
        val now = System.currentTimeMillis()
        val airing = now >= p.start && now < p.end
        heroPill.visibility = if (airing) View.VISIBLE else View.GONE
        heroPill.text = "LIVE"
        val range = "${timeFmt.format(Date(p.start))} – ${timeFmtA.format(Date(p.end))}"
        heroMeta.text = when {
            p.gap -> ch.name
            airing -> "${ch.name}  ·  $range  ·  ${((p.end - now) / MIN).toInt()} min left"
            else -> "${ch.name}  ·  $range"
        }
        heroTitle.text = p.title
        heroDetail.text = when {
            p.gap -> "No listing for this time slot — you can still tune in"
            p.detail.isNotBlank() -> p.detail
            else -> " "
        }
        heroHint.text = if (ch.hulu != null) "OK  Watch on Hulu      ☰  Favorite channel" else "OK  Open Hulu      ☰  Favorite channel"
    }

    private fun tick() {
        if (lineup != null) {
            // Refresh listings every 20 minutes; otherwise just move the now line.
            if (System.currentTimeMillis() - (lineup?.loadedAt ?: 0) > 20 * MIN) load() else render()
        }
        ui.postDelayed(::tick, 60_000 - (System.currentTimeMillis() % 60_000) + 100)
    }

    // ------------------------------------------------------------------ input

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (lineup == null) return super.onKeyDown(keyCode, event)
        val earliest = floor30(System.currentTimeMillis()) - 30 * MIN
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> if (row > 0) row--
            KeyEvent.KEYCODE_DPAD_DOWN -> if (row < channels.size - 1) row++
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val list = channels.getOrNull(row)?.let { programsFor(it) }.orEmpty()
                val cur = focusedProgram()
                val next = list.firstOrNull { cur != null && it.start >= cur.end }
                if (next != null) cursor = next.start + MIN
                while (cursor >= windowStart + (SPAN_MIN - 20) * MIN) windowStart += 30 * MIN
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                val list = channels.getOrNull(row)?.let { programsFor(it) }.orEmpty()
                val cur = focusedProgram()
                val prev = list.lastOrNull { cur != null && it.end <= cur.start }
                if (prev != null && prev.end > earliest) cursor = maxOf(prev.start, earliest) + MIN
                while (cursor < windowStart && windowStart > earliest) windowStart -= 30 * MIN
                // Keep the cursor inside what's visible.
                if (cursor < windowStart) cursor = windowStart + MIN
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> { watch(); return true }
            KeyEvent.KEYCODE_MENU -> { toggleFavorite(); return true }
            KeyEvent.KEYCODE_BACK -> { finish(); return true }
            else -> return super.onKeyDown(keyCode, event)
        }
        render()
        return true
    }

    private fun watch() {
        val ch = channels.getOrNull(row) ?: return
        val hulu = "com.hulu.plus"
        val link = ch.hulu
        val intent = if (link != null) Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(hulu)
        else packageManager.getLeanbackLaunchIntentForPackage(hulu) ?: packageManager.getLaunchIntentForPackage(hulu)
        if (intent == null) { Toast.makeText(this, "Hulu isn't installed on this TV", Toast.LENGTH_SHORT).show(); return }
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            if (link == null) Toast.makeText(this, "Opening Hulu — pick ${ch.name} in Live", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            // Link format not accepted: fall back to opening Hulu itself.
            packageManager.getLaunchIntentForPackage(hulu)?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    private fun toggleFavorite() {
        val ch = channels.getOrNull(row) ?: return
        val favs = favChannels()
        val adding = ch.id !in favs
        if (adding) favs.add(ch.id) else favs.remove(ch.id)
        getSharedPreferences("hearth", Context.MODE_PRIVATE).edit().putString("fav_channels", favs.joinToString(",")).apply()
        orderChannels()
        Toast.makeText(this, if (adding) "${ch.name} pinned to the top" else "${ch.name} unpinned", Toast.LENGTH_SHORT).show()
        render()
    }
}
