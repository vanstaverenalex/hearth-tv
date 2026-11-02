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
    private lateinit var modeDivider: View
    private val modeButtons = ArrayList<Pair<String, View>>()
    private lateinit var dock: LinearLayout
    private lateinit var dockGlass: GlassDrawable
    private lateinit var dockDivider: View
    private lateinit var favRow: RecyclerView
    private lateinit var favAdapter: TileAdapter
    private val dockButtons = ArrayList<Pair<FrameLayout, ImageView>>()

    private lateinit var clockBlock: LinearLayout
    private lateinit var clockHalo: SoftHalo
    private lateinit var brandHalo: SoftHalo
    private lateinit var santa: SantaSleigh
    private var resumed = false
    private val santaRun = object : Runnable {
        override fun run() {
            if (resumed && overlay == null && SantaSleigh.inSeason()) santa.fly()
            ui.postDelayed(this, 120_000L)
        }
    }
    private lateinit var topRight: LinearLayout
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
        if (intent?.getStringExtra(EXTRA_OPEN) == "guide") root.post { openGuide() }
    }

    /** Home button → always the home screen (overlays closed). Live/Guide button → our guide. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        when (intent.getStringExtra(EXTRA_OPEN)) {
            "guide" -> { resetHome(); openGuide() }
            else -> resetHome()
        }
    }

    /** Snap back to the plain home screen: no overlay, dock focused on the first app. */
    private fun resetHome() {
        if (overlay != null) {
            overlay = null
            for (p in listOf(library, guide)) { p.animate().cancel(); p.visibility = View.GONE; p.alpha = 1f; p.translationY = 0f }
            overlayDim.animate().cancel(); overlayDim.alpha = 0f; overlayDim.visibility = View.GONE
            for (v in listOf(dock, clockBlock, topRight, clockHalo, brandHalo)) {
                v.animate().cancel(); v.visibility = View.VISIBLE; v.alpha = 1f; v.translationY = 0f
            }
        }
        favRow.scrollToPosition(0)
        focusLive()
    }

    override fun onResume() {
        super.onResume()
        tick()
        reloadApps()
        if (System.currentTimeMillis() - lastWeatherAt > 20 * 60_000) refreshWeather()
        applyTheme(force = true)
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
        video.pause()
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

        // Soft halo behind the clock so it reads on any background
        clockHalo = SoftHalo(this)
        root.addView(clockHalo, FrameLayout.LayoutParams(dpi(10f), dpi(10f)))

        // Clock + weather, top-left
        clockBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        clockBlock.addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
            val lp = clockHalo.layoutParams as FrameLayout.LayoutParams
            val padX = dpi(110f); val padY = dpi(70f)
            val nw = (r - l) + padX * 2; val nh = (b - t) + padY * 2
            if (lp.width != nw || lp.height != nh || lp.leftMargin != l - padX || lp.topMargin != t - padY) {
                lp.width = nw; lp.height = nh; lp.leftMargin = l - padX; lp.topMargin = t - padY
                clockHalo.layoutParams = lp
            }
        }
        dateText = TextView(this).apply { style(14f, Fonts.body(this@MainActivity, 700), Color.WHITE, 0.08f); isAllCaps = true }
        clockText = TextView(this).apply {
            style(116f, Fonts.display(this@MainActivity, 300), Color.WHITE, -0.035f)
            setShadowLayer(dp(24f), 0f, dp(3f), Color.argb(64, 0, 0, 0))
        }
        val weatherRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        weatherIcon = ImageView(this)
        weatherTemp = TextView(this).apply { style(15f, Fonts.body(this@MainActivity, 700), Color.WHITE) }
        weatherDesc = TextView(this).apply { style(15f, Fonts.body(this@MainActivity, 500), Color.WHITE) }
        weatherRange = TextView(this).apply { style(15f, Fonts.body(this@MainActivity, 500), Color.argb(160, 255, 255, 255)) }
        weatherRow.addView(weatherIcon, LinearLayout.LayoutParams(dpi(20f), dpi(20f)).apply { rightMargin = dpi(8f) })
        weatherRow.addView(weatherTemp, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dpi(8f) })
        weatherRow.addView(weatherDesc, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dpi(10f) })
        weatherRow.addView(weatherRange)
        weatherRow.visibility = View.INVISIBLE
        weatherRow.tag = "weatherRow"
        clockBlock.addView(dateText)
        clockBlock.addView(clockText, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(6f); leftMargin = -dpi(4f) })
        clockBlock.addView(weatherRow, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dpi(10f) })
        root.addView(clockBlock, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            leftMargin = dpi(60f); topMargin = dpi(40f)
        })

        // Brand + mode switch, top-right (with its own soft halo)
        brandHalo = SoftHalo(this).apply { strength = 0.85f }
        root.addView(brandHalo, FrameLayout.LayoutParams(dpi(10f), dpi(10f)))
        topRight = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        topRight.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            val lp = brandHalo.layoutParams as FrameLayout.LayoutParams
            val padX = dpi(70f); val padY = dpi(40f)
            val nw = (r - l) + padX * 2; val nh = (b - t) + padY * 2
            if (lp.width != nw || lp.height != nh || lp.leftMargin != l - padX || lp.topMargin != t - padY) {
                lp.width = nw; lp.height = nh; lp.leftMargin = l - padX; lp.topMargin = t - padY
                brandHalo.layoutParams = lp
            }
        }
        val mark = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Hearth.PURPLE, Hearth.PINK)).apply {
                cornerRadius = dp(10f); setStroke(dpi(0.6f), Color.argb(86, 255, 255, 255))
            }
        }
        mark.addView(ImageView(this).apply { setImageResource(R.drawable.ic_network) },
            FrameLayout.LayoutParams(dpi(16f), dpi(16f), Gravity.CENTER))
        topRight.addView(mark, LinearLayout.LayoutParams(dpi(30f), dpi(30f)).apply { rightMargin = dpi(8f) })
        val brandTexts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brandName = TextView(this).apply { text = "Hearth"; style(16f, Fonts.display(this@MainActivity, 600), Color.WHITE) }
        brandSub = TextView(this).apply {
            text = "VAN STAVEREN HOUSEHOLD"; style(7.5f, Fonts.body(this@MainActivity, 700), Color.argb(190, 230, 233, 240), 0.08f)
            setPadding(0, dpi(2f), 0, 0)
        }
        brandTexts.addView(brandName); brandTexts.addView(brandSub)
        topRight.addView(brandTexts, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dpi(16f) })

        modeBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(4f), dpi(4f), dpi(4f), dpi(4f))
        }
        for ((key, icon) in listOf("light" to R.drawable.ic_sun, "dark" to R.drawable.ic_moon, "auto" to 0)) {
            val b: View = if (icon != 0) FrameLayout(this).apply {
                addView(ImageView(this@MainActivity).apply { setImageResource(icon) }, FrameLayout.LayoutParams(dpi(14f), dpi(14f), Gravity.CENTER))
            } else TextView(this).apply {
                text = "Auto"; gravity = Gravity.CENTER; style(11f, Fonts.body(this@MainActivity, 700), Color.WHITE)
                setPadding(dpi(10f), 0, dpi(10f), 0)
            }
            b.isFocusable = true
            b.setOnClickListener { prefs.mode = key; applyTheme(force = true) }
            b.setOnKeyListener { _, code, e ->
                if (code == KeyEvent.KEYCODE_MENU && e.action == KeyEvent.ACTION_UP) { showSetupStatus(); true } else false
            }
            b.setOnFocusChangeListener { _, _ -> styleModeBar() }
            modeButtons += key to b
            modeBar.addView(b, LinearLayout.LayoutParams(if (icon != 0) dpi(30f) else -2, dpi(30f)).apply { marginEnd = dpi(2f) })
        }
        // Divider, then ⏭ next background, in the same glass pill
        modeDivider = View(this)
        modeBar.addView(modeDivider, LinearLayout.LayoutParams(dpi(1f), dpi(16f)).apply { marginStart = dpi(3f); marginEnd = dpi(5f) })
        val skip = FrameLayout(this).apply {
            addView(ImageView(this@MainActivity).apply { setImageResource(R.drawable.ic_next) }, FrameLayout.LayoutParams(dpi(14f), dpi(14f), Gravity.CENTER))
            isFocusable = true; contentDescription = "Next background"
            setOnClickListener { skipScene() }
            setOnKeyListener { _, code, e ->
                if (code == KeyEvent.KEYCODE_MENU && e.action == KeyEvent.ACTION_UP) { showSetupStatus(); true } else false
            }
            setOnFocusChangeListener { _, _ -> styleModeBar() }
        }
        modeButtons += "skip" to skip
        modeBar.addView(skip, LinearLayout.LayoutParams(dpi(30f), dpi(30f)))
        topRight.addView(modeBar)
        root.addView(topRight, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            rightMargin = dpi(60f); topMargin = dpi(46f)
        })

        // Dock
        dock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            clipChildren = false; clipToPadding = false
            setPadding(dpi(11f - 7.5f), dpi(11f - 7.5f), dpi(11f - 7.5f), dpi(11f - 7.5f))
        }
        dockGlass = GlassDrawable(this, 28f, true)
        dock.background = dockGlass
        favAdapter = TileAdapter(repo, 128f, 72f, 15f, 17f, ::open, ::showAppMenu)
        favRow = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = favAdapter; itemAnimator = null
            clipChildren = false; clipToPadding = false; overScrollMode = View.OVER_SCROLL_NEVER
        }
        dock.addView(favRow, LinearLayout.LayoutParams(-2, -2))
        dockDivider = View(this)
        dock.addView(dockDivider, LinearLayout.LayoutParams(dpi(1f), dpi(48f)).apply { leftMargin = dpi(7.5f); rightMargin = dpi(7.5f) })
        liveButton = dockButton(R.drawable.ic_livetv, "Live TV") { openGuide() }
        dock.addView(liveButton)
        dock.addView(dockButton(R.drawable.ic_apps, "Apps") { openLibrary() })
        root.addView(dock, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dpi(38f)
        })

        buildOverlays()
    }

    private fun dockButton(icon: Int, label: String, action: () -> Unit): View {
        val size = 72f
        val pad = 44f
        val holder = FrameLayout(this).apply {
            clipChildren = false; isFocusable = true; contentDescription = label
        }
        val glow = GlowView(this, 17f, pad)
        holder.addView(glow, FrameLayout.LayoutParams(dpi(size + 2 * pad), dpi(size + 2 * pad)).apply {
            setMargins(-dpi(pad), -dpi(pad), -dpi(pad), -dpi(pad))
        })
        val face = FrameLayout(this)
        val img = ImageView(this).apply { setImageResource(icon) }
        face.addView(img, FrameLayout.LayoutParams(dpi(27f), dpi(27f), Gravity.CENTER))
        holder.addView(face, FrameLayout.LayoutParams(dpi(size), dpi(size)))
        holder.setOnClickListener { action() }
        holder.setOnFocusChangeListener { v, f ->
            animateScale(v, if (f) 1.06f else 1f)
            v.translationZ = if (f) 1f else 0f
            glow.show(f)
            styleDockButton(face, f)
        }
        dockButtons += face to img
        return holder.also {
            it.layoutParams = LinearLayout.LayoutParams(dpi(size), dpi(size)).apply { leftMargin = dpi(7.5f); rightMargin = dpi(7.5f) }
        }
    }

    private fun styleDockButton(face: FrameLayout, focused: Boolean) {
        face.background = LayerDrawable(arrayOf(
            if (focused) hearthGradient(this, 17f, 0.35f) else GradientDrawable().apply {
                cornerRadius = dp(17f); setColor(if (dark) Color.argb(22, 255, 255, 255) else Color.argb(115, 255, 255, 255))
            },
            GlassDrawable(this, 17f, dark),
        ))
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
        val nowDark = Display.isDark(prefs, weather)
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
        for (t in listOf(dateText, clockText, weatherTemp, weatherDesc, brandName)) t.setTextColor(ink)
        weatherRange.setTextColor(sub)
        brandSub.setTextColor(sub)
        clockHalo.dark = dark; brandHalo.dark = dark
        brandName.setShadowLayer(dp(8f), 0f, dp(1f), if (dark) Color.argb(110, 0, 0, 0) else Color.argb(170, 255, 255, 255))
        brandSub.setShadowLayer(dp(6f), 0f, dp(1f), if (dark) Color.argb(110, 0, 0, 0) else Color.argb(170, 255, 255, 255))
        // A gentle shadow on the text itself, on top of the halo.
        val shade = if (dark) Color.argb(110, 0, 0, 0) else Color.argb(170, 255, 255, 255)
        clockText.setShadowLayer(dp(18f), 0f, dp(2f), shade)
        for (t in listOf(dateText, weatherTemp, weatherDesc, weatherRange)) t.setShadowLayer(dp(8f), 0f, dp(1f), shade)
        weatherIcon.setColorFilter(ink)

        dockGlass.dark = dark; dockGlass.rebuild()
        dockDivider.setBackgroundColor(if (dark) Color.argb(56, 255, 255, 255) else Color.argb(40, 11, 23, 38))
        for ((face, img) in dockButtons) { img.setColorFilter(ink); styleDockButton(face, (face.parent as View).isFocused) }
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
        modeBar.background = GlassDrawable(this, 19f, dark)
        styleModeBar()

        if (changed || force) {
            worker.execute {
                val status = Display.applyBacklight(this, dark)
                android.util.Log.i("HearthDisplay", status)
            }
        }
        refreshScene()
    }

    private fun styleModeBar() {
        val ink = if (dark) Color.WHITE else Hearth.INK_DARK
        modeDivider.setBackgroundColor(Color.argb(60, Color.red(ink), Color.green(ink), Color.blue(ink)))
        for ((key, v) in modeButtons) {
            val selected = prefs.mode == key
            v.background = when {
                v.isFocused -> LayerDrawable(arrayOf(hearthGradient(this, 15f, 0.5f), rimDrawable(this, 15f, true, dark)))
                selected -> GradientDrawable().apply {
                    cornerRadius = dp(15f)
                    setColor(if (dark) Color.argb(62, 255, 255, 255) else Color.WHITE)
                }
                else -> null
            }
            val c = if (v.isFocused) Color.WHITE else if (selected) ink else Color.argb(150, Color.red(ink), Color.green(ink), Color.blue(ink))
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
        dateText.text = SimpleDateFormat("EEEE, MMMM d", Locale.US).format(now)
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
                    weatherRange.text = "H ${w.high}°  L ${w.low}°"
                    root.findViewWithTag<View>("weatherRow")?.visibility = View.VISIBLE
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
        favAdapter.items = prefs.favorites.mapNotNull { byPkg[it] }.take(6)
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
        for ((v, dy) in listOf(dock to dp(26f), clockBlock to -dp(12f), topRight to -dp(12f), clockHalo to -dp(12f), brandHalo to -dp(12f))) {
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
        for (v in listOf(dock, clockBlock, topRight, clockHalo, brandHalo)) {
            v.animate().cancel(); v.visibility = View.VISIBLE
            v.animate().alpha(1f).translationY(0f).setDuration(320).setStartDelay(60).setInterpolator(Ease.out).withLayer().start()
        }
        val btn = if (which == "apps") dock.getChildAt(dock.childCount - 1) else dock.getChildAt(dock.childCount - 2)
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
        if (lineup == null) { guideStatus.text = "Loading listings…"; guideStatus.visibility = View.VISIBLE }
        guideWorker.execute {
            val l = try { Guide.load(this) } catch (e: Exception) { null }
            ui.post {
                guideLoading = false
                if (l == null || l.channels.isEmpty()) {
                    if (lineup == null) guideStatus.text = "Couldn't load the channel list. Check the internet connection."
                    return@post
                }
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
        }
    }



    private fun watchChannel(ch: Guide.Channel) {
        val hulu = "com.hulu.plus"
        val link = ch.hulu
        val intent = if (link != null) Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(hulu)
        else packageManager.getLeanbackLaunchIntentForPackage(hulu) ?: packageManager.getLaunchIntentForPackage(hulu)
        android.util.Log.i("HearthGuide", "watch ${ch.id} -> ${link ?: "(Hulu home)"}")
        if (intent == null) { toast("Hulu isn't installed on this TV"); return }
        try {
            // Tested on the TV: while Hulu is open (profile picked) this link tunes straight to the channel.
            // Restarting Hulu (CLEAR_TASK) makes it show an error first, so never do that.
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            if (link == null) Toast.makeText(this, "Opening Hulu — pick ${ch.name} in Live", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            android.util.Log.w("HearthGuide", "watch link failed: $e")
            packageManager.getLaunchIntentForPackage(hulu)?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
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
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { toast("Couldn't open that") }
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
        /** "home" (Home button) or "guide" (remote's Live/Guide button), sent by [HomeWatcher]. */
        const val EXTRA_OPEN = "hearth_open"
    }
}
