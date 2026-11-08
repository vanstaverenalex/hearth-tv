package com.vanstaveren.hearthtv

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var repo: AppRepository
    private lateinit var prefs: Prefs
    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private var dark = true
    private var weather: WeatherNow? = null
    private var allApps: List<AppEntry> = emptyList()
    private val bgWorker = Executors.newSingleThreadExecutor()
    private var lastWeatherAt = 0L
    private var firstFocusDone = false
    private var libraryTab = "all"

    // Views
    private lateinit var root: FrameLayout
    private lateinit var video: LoopPlayer
    private lateinit var scrim: View
    private lateinit var dayText: TextView
    private lateinit var dateText: TextView
    private lateinit var clockText: TextView
    private lateinit var weatherIcon: ImageView
    private lateinit var weatherTemp: TextView
    private lateinit var weatherDesc: TextView
    private lateinit var weatherRange: TextView
    private lateinit var brandName: TextView
    private lateinit var brandSub: TextView
    private lateinit var modeBar: LinearLayout
    /** The dock's Live TV button: where focus rests whenever the home screen opens. */
    private var liveButton: View? = null
    private var appsButton: View? = null
    private val modeButtons = ArrayList<Pair<String, View>>()
    private lateinit var dock: LinearLayout
    private lateinit var dockGlass: GlassDrawable
    private lateinit var dockDivider: View
    private lateinit var favRow: RecyclerView
    private lateinit var favAdapter: TileAdapter
    private val dockButtons = ArrayList<DockTile>()

    private lateinit var card: LinearLayout
    private lateinit var cardGlass: GlassDrawable
    private lateinit var cardRule: View
    private lateinit var weatherRow: LinearLayout
    private lateinit var santa: SantaSleigh
    private var resumed = false
    private val santaRun = object : Runnable {
        override fun run() {
            if (resumed && overlay == null && SantaSleigh.inSeason()) santa.fly()
            ui.postDelayed(this, 120_000L)
        }
    }
    private lateinit var overlayDim: View
    private var overlay: String? = null            // "apps", "guide" or null

    private lateinit var library: FrameLayout
    private lateinit var libTitle: TextView
    private lateinit var libGrid: GlideGrid
    private lateinit var libAdapter: TileAdapter
    private lateinit var libBar: SegmentedBar
    private lateinit var libHint: TextView
    private val libTabs = listOf("all" to "All", "favorites" to "Favorites", "hidden" to "Hidden")

    private lateinit var guide: FrameLayout
    private lateinit var guideTitle: TextView
    private lateinit var guideBar: SegmentedBar
    private lateinit var guideView: GuideView
    private lateinit var guideHint: TextView
    private lateinit var guideStatus: TextView
    private lateinit var guideCredit: TextView
    private var lineup: Guide.Lineup? = null
    private var guideCats: List<Guide.Category> = emptyList()
    private var guideCat = "all"
    private var guideLoading = false
    private val guideWorker = Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = AppRepository(this)
        prefs = Prefs(this)
        buildViews()
        setContentView(root)
        video.scene.onGlassFailed = { useDrawnGlass() }
        root.viewTreeObserver.addOnPreDrawListener { pushGlass(); true }
        if (intent?.getStringExtra(EXTRA_OPEN) == "guide") root.post { openGuide() }
        intent?.let { root.post { handleShot(it) } }
    }

    /**
     * Screenshot helper (used by the PC's screenshot script over adb): `hearth_shot` = light|dark shows that look
     * without saving it or touching the backlight; `clear` goes back to the saved mode.
     */
    private var shotDark: Boolean? = null

    private fun handleShot(intent: Intent) {
        when (intent.getStringExtra(EXTRA_SHOT)) {
            null -> { if (shotDark == null) return; shotDark = null }   // any normal Home press ends a shot
            "light" -> shotDark = false
            "dark" -> shotDark = true
            else -> shotDark = null
        }
        applyTheme(force = true)
    }

    /** Home button → always the home screen (overlays closed). Live/Guide button → our guide. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShot(intent)
        when (intent.getStringExtra(EXTRA_OPEN)) {
            "guide" -> { resetHome(); openGuide() }
            "apps" -> { resetHome(); openLibrary() }
            else -> resetHome()
        }
    }

    /** Snap back to the plain home screen: no overlay, dock focused on the first app. */
    private fun resetHome() {
        if (overlay != null) {
            overlay = null
            for (p in listOf(library, guide)) { p.animate().cancel(); p.visibility = View.GONE; p.alpha = 1f; p.translationY = 0f }
            overlayDim.animate().cancel(); overlayDim.alpha = 0f; overlayDim.visibility = View.GONE
            for (v in listOf(dock, card)) {
                v.animate().cancel(); v.visibility = View.VISIBLE; v.alpha = 1f; v.translationY = 0f
            }
        }
        favRow.scrollToPosition(0)
        focusLive()
    }

    override fun onResume() {
        super.onResume()
        HomeWatcher.noteHearthShown()
        tick()
        reloadApps()
        if (System.currentTimeMillis() - lastWeatherAt > 20 * 60_000) refreshWeather()
        applyTheme(force = true)
        video.hostResume()
        video.resume()
        resumed = true
        ui.removeCallbacks(santaRun)
        ui.postDelayed(santaRun, 10_000L)
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        santa.stop()
        ui.removeCallbacksAndMessages(null)
        video.stop()     // never hold the decoder while another app is on screen
        video.hostPause()
    }

    // ------------------------------------------------------------------ layout

    private fun buildViews() {
        root = FrameLayout(this).apply {
            clipChildren = false
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xFF0A1030.toInt(), 0xFF2A2650.toInt(), 0xFF7C4C5E.toInt(), 0xFFC9805C.toInt(), 0xFF121430.toInt())
            )
        }

        video = LoopPlayer(this)
        root.addView(video, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))

        scrim = View(this)
        root.addView(scrim, FrameLayout.LayoutParams(-1, -1))

        // Holiday easter egg: Santa's sleigh across the very top (home screen only)
        santa = SantaSleigh(this)
        root.addView(santa, FrameLayout.LayoutParams(-1, dpi(46f), Gravity.TOP))

        // ---- One grid for the whole home screen: the info card and the dock share the same
        // 30dp margins, and the card is exactly as wide as the dock's Live TV + Apps pair, so its
        // right edge lines up with the dock's divider.
        val edge = dpi(EDGE)

        // ---- Glass info card, top-left: brand + controls, clock + date, weather
        card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false; clipToPadding = false
            setPadding(dpi(SH + 15f), dpi(SH + 12f), dpi(SH + 13.5f), dpi(SH + 15f))
        }
        cardGlass = GlassDrawable(this, PANEL_R, true, panel = true)
        card.background = null    // the GPU draws this glass; see pushGlass()

        // Header: mark · Hearth / Van Staveren Household · [☀ ☾ A] · ⏭ · ⚙
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            clipChildren = false; clipToPadding = false
        }
        val mark = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Hearth.PURPLE, Hearth.PINK)).apply {
                cornerRadius = dp(6.5f); setStroke(dpi(0.6f), Color.argb(86, 255, 255, 255))
            }
        }
        mark.addView(ImageView(this).apply { setImageResource(R.drawable.ic_network) },
            FrameLayout.LayoutParams(dpi(13f), dpi(13f), Gravity.CENTER))
        header.addView(mark, LinearLayout.LayoutParams(dpi(22.5f), dpi(22.5f)).apply { marginEnd = dpi(6f) })
        val brandTexts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brandName = TextView(this).apply { text = "Hearth"; style(11.5f, Fonts.display(this@MainActivity, 600), Color.WHITE) }
        brandSub = TextView(this).apply {
            text = "Van Staveren Household"; style(7.5f, Fonts.body(this@MainActivity, 600), Color.WHITE)
            maxLines = 1; setPadding(0, dpi(1.5f), 0, 0)
        }
        brandTexts.addView(brandName); brandTexts.addView(brandSub)
        header.addView(brandTexts, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dpi(6f) })

        modeBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            clipChildren = false; clipToPadding = false
            setPadding(dpi(1.5f), dpi(1.5f), dpi(1.5f), dpi(1.5f))
        }
        for ((key, icon) in listOf("light" to R.drawable.ic_sun, "dark" to R.drawable.ic_moon, "auto" to 0)) {
            val b: View = if (icon != 0) FrameLayout(this).apply {
                addView(ImageView(this@MainActivity).apply { setImageResource(icon) }, FrameLayout.LayoutParams(dpi(10.5f), dpi(10.5f), Gravity.CENTER))
            } else TextView(this).apply {
                text = "A"; gravity = Gravity.CENTER; style(8.5f, Fonts.body(this@MainActivity, 800), Color.WHITE)
            }
            b.contentDescription = when (key) { "light" -> "Light mode"; "dark" -> "Dark mode"; else -> "Automatic" }
            controlKeys(b, 1.06f) { prefs.mode = key; applyTheme(force = true) }
            modeButtons += key to b
            modeBar.addView(b, LinearLayout.LayoutParams(dpi(21f), dpi(21f)))
        }
        header.addView(modeBar, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dpi(6f) })

        val skip = circleButton(R.drawable.ic_next, 10.5f, "Next background") { skipScene() }
        header.addView(skip, LinearLayout.LayoutParams(dpi(24f), dpi(24f)).apply { marginEnd = dpi(6f) })
        val gear = circleButton(R.drawable.ic_settings, 11.5f, "Settings") {
            open(AppEntry(Specials.SETTINGS, null, "Settings", false))
        }
        header.addView(gear, LinearLayout.LayoutParams(dpi(24f), dpi(24f)))
        modeButtons += "skip" to skip
        modeButtons += "settings" to gear
        card.addView(header, LinearLayout.LayoutParams(-1, -2))

        cardRule = View(this)
        card.addView(cardRule, LinearLayout.LayoutParams(-1, dpi(1f)).apply { topMargin = dpi(10.5f) })

        // Clock row: big time, day + date on the right sitting on the same baseline
        val clockRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = true }
        clockText = TextView(this).apply {
            style(50f, Fonts.clock(this@MainActivity), Color.WHITE, -0.025f)
            fontFeatureSettings = "tnum"   // digits keep the same width, so the time never shifts
        }
        clockRow.addView(clockText, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = -dpi(2f) })
        val dayCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.END
        }
        dayText = TextView(this).apply { style(10.5f, Fonts.body(this@MainActivity, 700), Color.WHITE); gravity = Gravity.END }
        dateText = TextView(this).apply { style(10.5f, Fonts.body(this@MainActivity, 600), Color.WHITE); gravity = Gravity.END }
        dayCol.addView(dayText, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END })
        dayCol.addView(dateText, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END; topMargin = dpi(2.5f) })
        dayCol.baselineAlignedChildIndex = 1   // only valid once both lines are added
        clockRow.addView(dayCol, LinearLayout.LayoutParams(-2, -2))
        card.addView(clockRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(9f) })

        // Weather row
        weatherRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        weatherIcon = ImageView(this)
        weatherTemp = TextView(this).apply { style(19.5f, Fonts.body(this@MainActivity, 700), Color.WHITE) }
        val wCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        weatherDesc = TextView(this).apply { style(9.75f, Fonts.body(this@MainActivity, 600), Color.WHITE); maxLines = 1 }
        weatherRange = TextView(this).apply { style(9.75f, Fonts.body(this@MainActivity, 600), Color.WHITE); maxLines = 1 }
        wCol.addView(weatherDesc)
        wCol.addView(weatherRange, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(2f) })
        weatherRow.addView(weatherIcon, LinearLayout.LayoutParams(dpi(22.5f), dpi(22.5f)).apply { marginEnd = dpi(7.5f) })
        weatherRow.addView(weatherTemp, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dpi(7.5f) })
        weatherRow.addView(wCol, LinearLayout.LayoutParams(0, -2, 1f))
        weatherRow.alpha = 0f
        card.addView(weatherRow, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(10.5f) })

        // (the view is SH bigger on every side: that margin holds the glass's soft shadow)
        root.addView(card, FrameLayout.LayoutParams(cardWidthPx() + 2 * dpi(SH), -2, Gravity.TOP or Gravity.START).apply {
            leftMargin = edge - dpi(SH); topMargin = dpi(24f - SH)
        })

        // ---- Dock: full width on the same margins. Live TV · Apps | favourites
        // Vertical padding is half a gap because every tile carries half a gap of margin.
        dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            clipChildren = false; clipToPadding = false
            setPadding(dpi(SH + GAP), dpi(SH + GAP / 2), dpi(SH + GAP / 2), dpi(SH + GAP / 2))
        }
        dockGlass = GlassDrawable(this, PANEL_R, true, panel = true)
        dock.background = null
        liveButton = dockButton(R.drawable.ic_livetv, 22.5f, "Live TV") { openGuide() }
        appsButton = dockButton(R.drawable.ic_apps, 21f, "Apps") { openLibrary() }
        dock.addView(liveButton, LinearLayout.LayoutParams(dpi(TILE_W), dpi(TILE_H)).apply {
            topMargin = dpi(GAP / 2); bottomMargin = dpi(GAP / 2)
        })
        dock.addView(appsButton, LinearLayout.LayoutParams(dpi(TILE_W), dpi(TILE_H)).apply {
            leftMargin = dpi(GAP); topMargin = dpi(GAP / 2); bottomMargin = dpi(GAP / 2)
        })
        dockDivider = View(this)
        dock.addView(dockDivider, LinearLayout.LayoutParams(dpi(1f), dpi(42f)).apply {
            leftMargin = dpi(GAP); rightMargin = dpi(GAP / 2)
        })
        favAdapter = TileAdapter(repo, TILE_W, TILE_H, GAP, TILE_R, ::open, ::showAppMenu)
        favRow = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = favAdapter; itemAnimator = null
            clipChildren = false; clipToPadding = false; overScrollMode = View.OVER_SCROLL_NEVER
        }
        dock.addView(favRow, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(dock, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            leftMargin = edge - dpi(SH); rightMargin = edge - dpi(SH); bottomMargin = dpi(24f - SH)
        })

        buildOverlays()
    }

    /** Card width = dock padding + Live TV + gap + Apps + half a gap, so the card ends on the dock divider. */
    private fun cardWidthPx() = dpi(GAP) + 2 * dpi(TILE_W) + dpi(GAP) + dpi(GAP / 2)

    /** Favourite tiles share whatever is left of the dock evenly (never wider than Live TV / Apps). */
    private fun favTileWidthDp(count: Int): Float {
        if (count <= 0) return TILE_W
        val screenDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        val inner = screenDp - 2 * EDGE - GAP - GAP / 2          // dock width minus its padding
        val left = inner - (TILE_W + GAP + TILE_W + GAP + 1f + GAP / 2)
        return minOf(TILE_W, left / count - GAP)
    }

    /** D-pad wiring shared by every control in the card: OK acts, MENU opens setup status. */
    private fun controlKeys(v: View, lift: Float = 1.1f, action: () -> Unit) {
        v.isFocusable = true
        v.setOnClickListener { action() }
        v.setOnKeyListener { _, code, e ->
            if (code == KeyEvent.KEYCODE_MENU && e.action == KeyEvent.ACTION_UP) { showSetupStatus(); true } else false
        }
        v.setOnFocusChangeListener { b, f ->
            b.animate().cancel()
            b.animate().scaleX(if (f) lift else 1f).scaleY(if (f) lift else 1f)
                .setDuration(if (f) 220 else 180).setInterpolator(Ease.out).start()
            styleModeBar()
        }
    }

    private fun circleButton(icon: Int, iconDp: Float, label: String, action: () -> Unit) = FrameLayout(this).apply {
        addView(ImageView(this@MainActivity).apply { setImageResource(icon) }, FrameLayout.LayoutParams(dpi(iconDp), dpi(iconDp), Gravity.CENTER))
        contentDescription = label
        controlKeys(this, action = action)
    }

    private class DockTile(val face: FrameLayout, val icon: ImageView, val label: TextView)

    private fun dockButton(icon: Int, iconDp: Float, label: String, action: () -> Unit): View {
        val pad = 44f
        val holder = FrameLayout(this).apply {
            clipChildren = false; clipToPadding = false; isFocusable = true; contentDescription = label
        }
        val glow = GlowView(this, TILE_R, pad)
        holder.addView(glow, FrameLayout.LayoutParams(dpi(TILE_W + 2 * pad), dpi(TILE_H + 2 * pad)).apply {
            setMargins(-dpi(pad), -dpi(pad), -dpi(pad), -dpi(pad))
        })
        val face = FrameLayout(this)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val img = ImageView(this).apply { setImageResource(icon) }
        val text = TextView(this).apply { this.text = label; style(9.75f, Fonts.body(this@MainActivity, 800), Color.WHITE) }
        col.addView(img, LinearLayout.LayoutParams(dpi(iconDp), dpi(iconDp)))
        col.addView(text, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(5f) })
        face.addView(col, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        holder.addView(face, FrameLayout.LayoutParams(dpi(TILE_W), dpi(TILE_H)))
        val tile = DockTile(face, img, text)
        holder.setOnClickListener { action() }
        holder.setOnFocusChangeListener { v, f ->
            animateScale(v, if (f) 1.06f else 1f)
            v.translationZ = if (f) 1f else 0f
            glow.show(f)
            styleDockButton(tile, f)
        }
        dockButtons += tile
        styleDockButton(tile, false)
        return holder
    }

    private fun styleDockButton(t: DockTile, focused: Boolean) {
        val ink = if (dark || focused) Color.WHITE else Hearth.INK_DARK
        t.icon.setColorFilter(ink); t.label.setTextColor(ink)
        t.face.background = if (focused) LayerDrawable(arrayOf(
            GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Hearth.PURPLE, Hearth.PINK)).apply { cornerRadius = dp(TILE_R) },
            rimDrawable(this, TILE_R, true, dark),
        )) else if (gpuGlass) null else GlassDrawable(this, TILE_R, dark)
    }

    // ------------------------------------------------------------------ liquid glass (GPU)

    /** True while the GPU draws the home screen's glass (card, dock, Live TV / Apps tiles). */
    private var gpuGlass = true
    private val loc = IntArray(2)

    /** The video's GL layer can't do glass on this TV: go back to drawn glass. */
    private fun useDrawnGlass() {
        if (!gpuGlass) return
        gpuGlass = false
        Diag.log(this, "glass: GPU glass unavailable, using drawn glass")
        card.background = cardGlass; dock.background = dockGlass
        for (t in dockButtons) styleDockButton(t, (t.face.parent as View).isFocused)
        video.scene.setPanels(emptyList())
    }

    /** Tell the GL layer where each pane of glass is right now (called before every UI frame). */
    private fun pushGlass() {
        if (!gpuGlass) return
        val sh = dpi(SH).toFloat()
        val list = ArrayList<GlassScene.Panel>(4)
        fun pane(v: View, inset: Float, radiusDp: Float, alpha: Float, frost: Float, shadow: Float) {
            if (!v.isShown || alpha <= 0.004f || v.width == 0) return
            v.getLocationInWindow(loc)
            var sx = 1f; var sy = 1f
            var p: View? = v
            while (p != null && p !== root) { sx *= p.scaleX; sy *= p.scaleY; p = p.parent as? View }
            val l = loc[0] + inset * sx; val t = loc[1] + inset * sy
            list += GlassScene.Panel(l, t, l + (v.width - 2 * inset) * sx, t + (v.height - 2 * inset) * sy,
                dp(radiusDp) * sx, alpha, frost, shadow)
        }
        val shadow = if (dark) 0.42f else 0.16f
        pane(card, sh, PANEL_R, card.alpha, 0.90f, shadow)
        pane(dock, sh, PANEL_R, dock.alpha, 0.82f, shadow)
        for (t in dockButtons) {
            val holder = t.face.parent as View
            if (!holder.isFocused) pane(t.face, 0f, TILE_R, dock.alpha, 0.55f, 0f)
        }
        video.scene.setPanels(list)
    }

    private fun headerTitle(text: String) = TextView(this).apply {
        this.text = text; style(30f, Fonts.display(this@MainActivity, 400), Color.WHITE, -0.02f)
    }

    /** Apps and Live TV share one look: same dim over the live background, header, glass tabs. */
    private fun buildOverlays() {
        overlayDim = View(this).apply { alpha = 0f; visibility = View.GONE }
        root.addView(overlayDim, FrameLayout.LayoutParams(-1, -1))

        // ---- Apps
        library = FrameLayout(this).apply { visibility = View.GONE; clipChildren = false }
        libTitle = headerTitle("Apps")
        library.addView(libTitle, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            leftMargin = dpi(70f); topMargin = dpi(36f)
        })
        libBar = SegmentedBar(this).apply {
            items = libTabs.map { it.second }
            onSelect = { i -> libraryTab = libTabs[i].first; renderLibrary() }
            setOnKeyListener { _, code, e ->
                if (code == KeyEvent.KEYCODE_DPAD_DOWN && e.action == KeyEvent.ACTION_DOWN) { libGrid.getChildAt(0)?.requestFocus(); true } else false
            }
        }
        library.addView(libBar, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            rightMargin = dpi(70f); topMargin = dpi(40f)
        })
        libAdapter = TileAdapter(repo, 146f, 82f, 18f, 18f, ::open, ::showAppMenu)
        // Full-screen grid: the 5 columns sit centered via padding, so focus glows have room on every
        // side, and rows fade out under the header instead of being cut by the grid's edge.
        libGrid = GlideGrid(this).apply {
            layoutManager = SmoothGridLayoutManager(this@MainActivity, 5)
            adapter = libAdapter; itemAnimator = null
            setHasFixedSize(true); setItemViewCacheSize(30)
            clipChildren = false; clipToPadding = false
            fadeTopStart = dp(78f); fadeTopEnd = dp(108f)
            addOnLayoutChangeListener { v, l, _, r, _, _, _, _, _ ->
                val side = maxOf(0, ((r - l) - dpi((146f + 18f) * 5)) / 2)
                if (v.paddingLeft != side) v.setPadding(side, dpi(104f), side, dpi(70f))
            }
            setPadding(0, dpi(104f), 0, dpi(70f))
        }
        library.addView(libGrid, 0, FrameLayout.LayoutParams(-1, -1))
        libHint = TextView(this).apply {
            text = "☰  Pin, reorder or hide"; gravity = Gravity.CENTER
            style(10f, Fonts.body(this@MainActivity, 600), Color.argb(215, 255, 255, 255))
            setPadding(dpi(13f), 0, dpi(13f), 0)
        }
        library.addView(libHint, FrameLayout.LayoutParams(-2, dpi(28f), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dpi(22f)
        })
        root.addView(library, FrameLayout.LayoutParams(-1, -1))

        // ---- Live TV
        guide = FrameLayout(this).apply { visibility = View.GONE; clipChildren = false }
        guideTitle = headerTitle("Live TV")
        guide.addView(guideTitle, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            leftMargin = dpi(70f); topMargin = dpi(36f)
        })
        guideBar = SegmentedBar(this).apply {
            items = listOf("All")
            onSelect = { i -> guideCats.getOrNull(i)?.let { guideCat = it.id; guideView.setCategory(it.id) } }
            setOnKeyListener { _, code, e ->
                if (code == KeyEvent.KEYCODE_DPAD_DOWN && e.action == KeyEvent.ACTION_DOWN) { guideView.requestFocus(); true } else false
            }
        }
        guide.addView(guideBar, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            rightMargin = dpi(70f); topMargin = dpi(40f)
        })

        guideHint = TextView(this).apply {
            text = "OK  Watch on Hulu     ☰  Favorite     ▶▶  Later shows"
            style(8.5f, Fonts.body(this@MainActivity, 700), Color.WHITE, 0.02f)
        }
        guide.addView(guideHint, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = dpi(76f); bottomMargin = dpi(16f)
        })

        guideView = GuideView(this).apply {
            onWatch = { ch -> watchChannel(ch) }
            onExitUp = { guideBar.requestFocus() }
        }
        guide.addView(guideView, FrameLayout.LayoutParams(-1, -1).apply {
            leftMargin = dpi(70f); rightMargin = dpi(66f); topMargin = dpi(98f); bottomMargin = dpi(40f)
        })
        // The card's breathing glow sits on its own layer right above the grid.
        guide.addView(guideView.rim, FrameLayout.LayoutParams(-1, -1).apply {
            leftMargin = dpi(70f); rightMargin = dpi(66f); topMargin = dpi(98f); bottomMargin = dpi(40f)
        })
        guideStatus = TextView(this).apply {
            text = "Loading listings…"; gravity = Gravity.CENTER
            style(13f, Fonts.body(this@MainActivity, 600), Color.WHITE)
        }
        guide.addView(guideStatus, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply { topMargin = dpi(80f) })
        guideCredit = TextView(this).apply {
            text = "Listings: Gracenote · TVmaze · ESPN"
            style(7.5f, Fonts.body(this@MainActivity, 600), Color.WHITE)
        }
        guideCredit.visibility = View.GONE
        guide.addView(guideCredit, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            rightMargin = dpi(76f); bottomMargin = dpi(17f)
        })
        root.addView(guide, FrameLayout.LayoutParams(-1, -1))
    }

    // ------------------------------------------------------------------ theme

    private fun applyTheme(force: Boolean = false) {
        val nowDark = shotDark ?: Display.isDark(prefs, weather)
        if (!force && nowDark == dark) return
        val changed = nowDark != dark
        dark = nowDark
        val ink = if (dark) Color.WHITE else Hearth.INK_DARK
        val sub = if (dark) Color.argb(160, 255, 255, 255) else Color.argb(185, 11, 23, 38)

        scrim.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            if (dark) intArrayOf(0x8C03050E.toInt(), 0x1F03050E, 0x0003050E, 0x5903050E, 0xB303050E.toInt())
            else intArrayOf(0x73FFFFFF, 0x1AFFFFFF, 0x00FFFFFF, 0x1F08283C, 0x4D08283C)
        )
        fun inkA(a: Int) = Color.argb(a, Color.red(ink), Color.green(ink), Color.blue(ink))
        for (t in listOf(dayText, clockText, weatherTemp, weatherDesc, brandName)) t.setTextColor(ink)
        brandSub.setTextColor(inkA(184))
        dateText.setTextColor(inkA(190))
        weatherRange.setTextColor(inkA(178))
        weatherIcon.setColorFilter(ink)
        cardGlass.dark = dark
        video.scene.dark = dark
        cardRule.setBackgroundColor(inkA(if (dark) 41 else 31))

        dockGlass.dark = dark
        dockDivider.setBackgroundColor(inkA(if (dark) 51 else 41))
        for (t in dockButtons) styleDockButton(t, (t.face.parent as View).isFocused)
        favAdapter.dark = dark
        libAdapter.dark = dark
        overlayDim.setBackgroundColor(if (dark) Color.argb(168, 4, 6, 18) else Color.argb(150, 236, 242, 248))
        video.veilColor = if (dark) 0xFF080C1E.toInt() else 0xFFE6EEF6.toInt()
        for (t in listOf(libTitle, guideTitle)) t.setTextColor(ink)
        for (t in listOf(guideHint, guideStatus)) t.setTextColor(sub)
        guideCredit.setTextColor(Color.argb(110, Color.red(ink), Color.green(ink), Color.blue(ink)))
        libHint.setTextColor(if (dark) Color.argb(215, 255, 255, 255) else Hearth.INK_DARK)
        libHint.background = GlassDrawable(this, 14f, dark)
        libBar.dark = dark; guideBar.dark = dark; guideView.dark = dark
        libGrid.dark = dark
        styleModeBar()

        if ((changed || force) && shotDark == null) {
            worker.execute {
                val status = Display.applyBacklight(this, dark)
                android.util.Log.i("HearthDisplay", status)
            }
        }
        refreshScene()
    }

    /** Segmented light/dark/auto control plus the round ⏭ and ⚙ buttons in the card header. */
    private fun styleModeBar() {
        val ink = if (dark) Color.WHITE else Hearth.INK_DARK
        fun inkA(a: Int) = Color.argb(a, Color.red(ink), Color.green(ink), Color.blue(ink))
        modeBar.background = GradientDrawable().apply {
            cornerRadius = dp(12f); setColor(if (dark) Color.argb(51, 0, 0, 0) else Color.argb(18, 11, 23, 38))
        }
        for ((key, v) in modeButtons) {
            val round = key == "skip" || key == "settings"
            val r = if (round) 12f else 10.5f
            val selected = !round && prefs.mode == key
            v.background = when {
                v.isFocused -> LayerDrawable(arrayOf(
                    GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Hearth.PURPLE, Hearth.PINK)).apply { cornerRadius = dp(r) },
                    rimDrawable(this, r, true, dark)))
                selected -> GradientDrawable().apply {
                    cornerRadius = dp(r); setColor(if (dark) Color.argb(235, 255, 255, 255) else Hearth.INK_DARK)
                }
                round -> GradientDrawable().apply { cornerRadius = dp(r); setColor(inkA(if (dark) 26 else 18)) }
                else -> null
            }
            val c = when {
                v.isFocused -> Color.WHITE
                selected -> if (dark) Hearth.INK_DARK else Color.WHITE
                round -> ink
                else -> inkA(170)
            }
            if (v is TextView) v.setTextColor(c)
            else ((v as FrameLayout).getChildAt(0) as ImageView).setColorFilter(c)
        }
    }

    private var sceneSince = 0L
    private var skipping = false

    /** Start the live background (the saved clip), then move on by itself every 40 minutes. */
    private fun refreshScene() {
        val now = System.currentTimeMillis()
        if (video.current != null && now - sceneSince < 40 * 60_000L) return
        val advance = video.current != null
        sceneSince = now
        bgWorker.execute {
            val f = if (advance) Backgrounds.next(this) else Backgrounds.current(this)
            if (f != null) ui.post { video.play(f) }
            Backgrounds.prefetch(this)
        }
    }

    /** ⏭ in the mode pill: next background right away. */
    private fun skipScene() {
        if (skipping) return
        skipping = true
        sceneSince = System.currentTimeMillis()
        bgWorker.execute {
            val f = Backgrounds.next(this)
            ui.post { skipping = false; if (f != null) video.play(f) }
            Backgrounds.prefetch(this)
        }
    }

    // ------------------------------------------------------------------ clock & weather

    private fun tick() {
        val now = Date()
        clockText.text = SimpleDateFormat("h:mm", Locale.US).format(now)
        dayText.text = SimpleDateFormat("EEEE", Locale.US).format(now)
        dateText.text = SimpleDateFormat("MMMM d", Locale.US).format(now)
        applyTheme()
        refreshScene()
        if (overlay == "guide") {
            guideView.tick()
            if (System.currentTimeMillis() - (lineup?.loadedAt ?: 0L) > 20 * 60_000L) loadGuide()
        }
        ui.postDelayed(::tick, 60_000 - (System.currentTimeMillis() % 60_000) + 50)
    }

    private fun refreshWeather() {
        worker.execute {
            val w = Weather.fetch()
            ui.post {
                if (w != null) {
                    weather = w
                    lastWeatherAt = System.currentTimeMillis()
                    val (icon, words) = Weather.describe(w.code, w.isDay)
                    weatherIcon.setImageResource(icon)
                    weatherTemp.text = "${w.temp}°"
                    weatherDesc.text = words
                    weatherRange.text = "H ${w.high}° · L ${w.low}°"
                    if (weatherRow.alpha < 1f) weatherRow.animate().alpha(1f).setDuration(420).setInterpolator(Ease.out).start()
                    applyTheme()
                }
                ui.postDelayed(::refreshWeather, 30 * 60_000L)
            }
        }
    }

    // ------------------------------------------------------------------ apps

    private fun reloadApps() {
        worker.execute {
            val apps = repo.load()
            ui.post {
                allApps = apps
                prefs.seedFavorites(apps.map { it.pkg })
                renderDock()
                if (overlay == "apps") renderLibrary()
                if (!firstFocusDone) {
                    firstFocusDone = true
                    focusLive()
                }
            }
        }
    }

    private fun renderDock() {
        val byPkg = allApps.associateBy { it.pkg }
        val favs = prefs.favorites.mapNotNull { byPkg[it] }.take(5)
        favAdapter.tileWdp = favTileWidthDp(favs.size)
        favAdapter.items = favs
        dockDivider.visibility = if (favAdapter.itemCount > 0) View.VISIBLE else View.GONE
    }

    private fun renderLibrary() {
        val hidden = prefs.hidden
        val byPkg = allApps.associateBy { it.pkg }
        libAdapter.items = when (libraryTab) {
            "favorites" -> prefs.favorites.mapNotNull { byPkg[it] }
            "hidden" -> allApps.filter { it.pkg in hidden }
            else -> allApps.filter { it.pkg !in hidden } +
                AppEntry(Specials.FIRE_HOME, null, "Fire TV Home", false) +
                AppEntry(Specials.SETTINGS, null, "Settings", false)
        }
    }

    private fun openLibrary() {
        libraryTab = "all"
        libBar.select(0, animate = false)
        renderLibrary()
        showOverlay("apps")
        libGrid.post { libGrid.getChildAt(0)?.requestFocus() }
    }

    private fun showOverlay(which: String) {
        overlay = which
        santa.stop()
        val panel = if (which == "apps") library else guide
        val other = if (which == "apps") guide else library
        other.animate().cancel(); other.visibility = View.GONE
        overlayDim.animate().cancel()
        overlayDim.visibility = View.VISIBLE
        overlayDim.animate().alpha(1f).setDuration(280).setStartDelay(0).setInterpolator(Ease.out).withLayer().start()
        // The home screen steps back: light/dark switch, clock and dock all hide.
        for ((v, dy) in listOf(dock to dp(26f), card to -dp(14f))) {
            v.animate().cancel()
            v.animate().alpha(0f).translationY(dy).setDuration(200).setStartDelay(0).setInterpolator(Ease.out).withLayer()
                .withEndAction { if (overlay != null) v.visibility = View.INVISIBLE }.start()
        }
        panel.animate().cancel()
        panel.alpha = 0f; panel.translationY = dp(18f); panel.visibility = View.VISIBLE
        panel.animate().alpha(1f).translationY(0f).setDuration(340).setStartDelay(40).setInterpolator(Ease.out).withLayer().start()
    }

    private fun hideOverlay() {
        val which = overlay ?: return
        overlay = null
        val panel = if (which == "apps") library else guide
        panel.animate().cancel()
        panel.animate().alpha(0f).translationY(dp(12f)).setDuration(200).setStartDelay(0).setInterpolator(Ease.inOut).withLayer()
            .withEndAction { if (overlay != which) panel.visibility = View.GONE }.start()
        overlayDim.animate().cancel()
        overlayDim.animate().alpha(0f).setDuration(260).setInterpolator(Ease.inOut).withLayer()
            .withEndAction { if (overlay == null) overlayDim.visibility = View.GONE }.start()
        for (v in listOf(dock, card)) {
            v.animate().cancel(); v.visibility = View.VISIBLE
            v.animate().alpha(1f).translationY(0f).setDuration(320).setStartDelay(60).setInterpolator(Ease.out).withLayer().start()
        }
        val btn = if (which == "apps") appsButton else liveButton
        dock.post { btn?.requestFocus() }
    }

    // ------------------------------------------------------------------ live tv

    private fun favChannels(): MutableSet<String> =
        getSharedPreferences("hearth", MODE_PRIVATE).getString("fav_channels", "")!!
            .split(",").filter { it.isNotBlank() }.toMutableSet()

    private fun openGuide() {
        guideView.resetToNow()
        showOverlay("guide")
        if (lineup == null || System.currentTimeMillis() - (lineup?.loadedAt ?: 0L) > 20 * 60_000L) loadGuide()
        else guideView.post { guideView.requestFocus() }
    }

    private fun loadGuide() {
        if (guideLoading) return
        guideLoading = true
        val firstEver = lineup == null
        if (firstEver) { guideStatus.text = "Loading listings…"; guideStatus.visibility = View.VISIBLE }
        // If this takes a while (slow network), say so instead of looking frozen.
        val slowNote = Runnable { if (guideLoading && lineup == null) guideStatus.text = "Still loading listings… the internet seems slow" }
        root.postDelayed(slowNote, 12_000)
        guideWorker.execute {
            // 1) What's saved on the TV shows instantly; 2) fresh listings replace it when they arrive.
            if (firstEver) {
                val saved = try { Guide.load(this, offline = true) } catch (e: Exception) { null }
                if (saved != null && saved.channels.isNotEmpty()) ui.post { if (lineup == null) showLineup(saved) }
            }
            val l = try { Guide.load(this) } catch (e: Exception) { Diag.log(this, "guide: load failed: $e"); null }
            ui.post {
                guideLoading = false
                root.removeCallbacks(slowNote)
                if (l == null || l.channels.isEmpty()) {
                    if (lineup == null) guideStatus.text = "Couldn't load the channel list. Check the internet connection."
                    return@post
                }
                showLineup(l)
            }
        }
    }

    private fun showLineup(l: Guide.Lineup) {
        val first = lineup == null
        lineup = l
        val favs = favChannels()
        val present = l.channels.map { it.category }.toSet()
        guideCats = Guide.categories.filter { c ->
            c.id == "all" || (c.id == "favorites" && favs.isNotEmpty()) || c.id in present
        }.ifEmpty { listOf(Guide.Category("all", "All")) }
        if (guideCats.none { it.id == guideCat }) guideCat = "all"
        guideBar.items = guideCats.map { it.name }
        guideBar.select(guideCats.indexOfFirst { it.id == guideCat }.coerceAtLeast(0), animate = false)
        guideView.setData(l, favs, guideCat)
        guideStatus.visibility = View.GONE
        if (first && overlay == "guide") guideView.post { guideView.requestFocus() }
    }

    private fun watchChannel(ch: Guide.Channel) {
        val hulu = "com.hulu.plus"
        val link = ch.hulu
        val intent = if (link != null) Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(hulu)
        else packageManager.getLeanbackLaunchIntentForPackage(hulu) ?: packageManager.getLaunchIntentForPackage(hulu)
        HomeWatcher.noteLaunch(this, hulu, amazonUi = false)
        Diag.log(this, "watch ${ch.id} -> ${link ?: "(Hulu home)"}")
        if (intent == null) { toast("Hulu isn't installed on this TV"); return }
        launchSoon {
            try {
                // Tested on the TV: while Hulu is open (profile picked) this link tunes straight to the channel.
                // Restarting Hulu (CLEAR_TASK) makes it show an error first, so never do that.
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                if (link == null) Toast.makeText(this, "Opening Hulu — pick ${ch.name} in Live", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Diag.log(this, "watch link failed: $e")
                try { packageManager.getLaunchIntentForPackage(hulu)?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
                catch (_: Exception) { video.resume() }
            }
        }
    }

    private fun toggleChannelFavorite() {
        val ch = guideView.focusedChannel() ?: return
        val favs = favChannels()
        val adding = favs.add(ch.id)
        if (!adding) favs.remove(ch.id)
        getSharedPreferences("hearth", MODE_PRIVATE).edit().putString("fav_channels", favs.joinToString(",")).apply()
        guideView.setFavorites(favs)
        toast(if (adding) "${ch.name} added to Favorites" else "${ch.name} removed from Favorites")
        lineup?.let { l ->
            val present = l.channels.map { it.category }.toSet()
            guideCats = Guide.categories.filter { c -> c.id == "all" || (c.id == "favorites" && favs.isNotEmpty()) || c.id in present }
            guideBar.items = guideCats.map { it.name }
            guideBar.select(guideCats.indexOfFirst { it.id == guideCat }.coerceAtLeast(0), animate = false)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode in HomeWatcher.GUIDE_KEYS) { if (overlay != "guide") { resetHome(); openGuide() }; return true }
        if (overlay == "guide" && keyCode == KeyEvent.KEYCODE_MENU) { toggleChannelFavorite(); return true }
        return super.onKeyDown(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (overlay != null) hideOverlay()
        else focusLive()
    }

    private fun focusLive() { dock.post { liveButton?.requestFocus() } }

    private fun open(app: AppEntry) {
        HomeWatcher.noteLaunch(this, app.pkg, amazonUi = app.pkg == Specials.SETTINGS || app.pkg in AMAZON_SCREENS)
        when (app.pkg) {
            Specials.SETTINGS -> safeStart(Intent(Settings.ACTION_SETTINGS))
            Specials.FIRE_HOME -> {
                HomeWatcher.pause(this, 3 * 60_000L)
                val i = packageManager.getLaunchIntentForPackage("com.amazon.tv.launcher")
                    ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage("com.amazon.tv.launcher")
                safeStart(i)
            }
            else -> repo.launchIntent(app)?.let { safeStart(it) } ?: toast("Can't open ${app.label}")
        }
    }

    private fun safeStart(i: Intent) {
        Diag.log(this, "start ${i.component?.flattenToShortString() ?: i.action} pkg=${i.`package`}")
        launchSoon {
            try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {
                Diag.log(this, "start failed: $e"); toast("Couldn't open that"); video.resume()
            }
        }
    }

    /**
     * Free the background video's hardware decoder first, then open the app a moment later.
     * Hulu (and other streaming apps) show "reload or quit" when they start while a decoder is still busy.
     */
    private fun launchSoon(go: () -> Unit) {
        video.stop()
        root.postDelayed(go, 150)
    }

    private fun showAppMenu(app: AppEntry) {
        if (app.isSpecial) return
        val favs = prefs.favorites
        val i = favs.indexOf(app.pkg)
        val hidden = app.pkg in prefs.hidden
        val items = ArrayList<GlassMenu.Item>()
        if (i >= 0) {
            if (i > 0) items += GlassMenu.Item(R.drawable.ic_left, "Move left") { move(app.pkg, -1) }
            if (i < favs.size - 1) items += GlassMenu.Item(R.drawable.ic_right, "Move right") { move(app.pkg, 1) }
            items += GlassMenu.Item(R.drawable.ic_star_off, "Remove from favorites") {
                prefs.favorites = favs.apply { remove(app.pkg) }; refreshAll()
            }
        } else {
            items += GlassMenu.Item(R.drawable.ic_star, "Add to favorites") {
                prefs.favorites = favs.apply { add(app.pkg) }; refreshAll()
            }
        }
        items += if (hidden) GlassMenu.Item(R.drawable.ic_show, "Show in Apps") {
            prefs.hidden = prefs.hidden.apply { remove(app.pkg) }; refreshAll()
        } else GlassMenu.Item(R.drawable.ic_hide, "Hide from Apps") {
            prefs.hidden = prefs.hidden.apply { add(app.pkg) }; refreshAll()
        }
        items += GlassMenu.Item(R.drawable.ic_info, "App info") {
            safeStart(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}")))
        }
        val sub = if (i >= 0) "Favorite · spot ${i + 1} of ${favs.size}" else null
        GlassMenu(this, dark, app.label, sub, repo.banners[app.pkg] ?: repo.icons[app.pkg], items).show()
    }

    private fun move(pkg: String, delta: Int) {
        val favs = prefs.favorites
        val i = favs.indexOf(pkg); val j = i + delta
        if (i < 0 || j !in favs.indices) return
        favs.removeAt(i); favs.add(j, pkg); prefs.favorites = favs
        refreshAll()
        favRow.post { favRow.layoutManager?.findViewByPosition(j)?.requestFocus() }
    }

    private fun refreshAll() {
        renderDock()
        if (overlay == "apps") renderLibrary()
    }

    /** ☰ on the light/dark switch: shows what setup has been done on this TV. */
    private fun showSetupStatus() {
        worker.execute {
            val found = Display.scan(this)
            val home = Display.homeTakeoverOn(this)
            val write = Display.canWriteSecure(this)
            ui.post {
                val lines = ArrayList<GlassMenu.Item>()
                lines += GlassMenu.Item(R.drawable.ic_network, if (home) "Home button: Hearth ✓" else "Home button: not set up yet") {}
                lines += GlassMenu.Item(R.drawable.ic_sun,
                    if (found.isEmpty()) "Backlight: via screen brightness${if (write) " ✓" else " (needs setup)"}" else "Backlight: ${found.first().key}${if (write) " ✓" else " (needs setup)"}") {}
                getSharedPreferences("hearth", MODE_PRIVATE).getString("last_key", null)?.let {
                    lines += GlassMenu.Item(R.drawable.ic_info, "Last special remote button: $it") {}
                }
                lines += GlassMenu.Item(R.drawable.ic_info, "Version ${packageManager.getPackageInfo(packageName, 0).versionName}") {}
                GlassMenu(this, dark, "Hearth setup", "This TV", null, lines).show()
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    @Suppress("unused")
    private fun ViewGroup.firstFocusable(): View? = (0 until childCount).map { getChildAt(it) }.firstOrNull { it.isFocusable }

    companion object {
        /** Home grid, in dp: outer margin, gap, tile size and the shared panel/tile radii. */
        private const val EDGE = 30f
        private const val GAP = 10.5f
        private const val TILE_W = 136f
        private const val TILE_H = 75f
        private const val TILE_R = 13.5f
        private const val PANEL_R = 22.5f
        /** Room around the card and dock for their glass shadow. */
        private const val SH = GlassDrawable.SHADOW_DP
        /** "home" (Home button) or "guide" (remote's Live/Guide button), sent by [HomeWatcher]. */
        const val EXTRA_OPEN = "hearth_open"
        /** Amazon apps whose screens live inside the launcher package (Appstore, Settings, inputs). */
        val AMAZON_SCREENS = setOf("com.amazon.venezia", "com.amazon.tv.settings.v2", "com.amazon.tv.settings", "com.amazon.tv.launcher", "com.amazon.firebat", "com.amazon.tv.livetv", "com.amazon.tv.inputpreference.service")
        const val EXTRA_SHOT = "hearth_shot"
    }
}
