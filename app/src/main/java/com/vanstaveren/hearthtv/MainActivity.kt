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
import android.widget.VideoView
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
    private var playingFile: File? = null
    private var lastWeatherAt = 0L
    private var firstFocusDone = false
    private var libraryTab = "all"

    // Views
    private lateinit var root: FrameLayout
    private lateinit var video: VideoView
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
    private val modeButtons = ArrayList<Pair<String, View>>()
    private lateinit var dock: LinearLayout
    private lateinit var dockGlass: GlassDrawable
    private lateinit var dockDivider: View
    private lateinit var favRow: RecyclerView
    private lateinit var favAdapter: TileAdapter
    private val dockButtons = ArrayList<Pair<FrameLayout, ImageView>>()

    private lateinit var library: FrameLayout
    private lateinit var libDim: View
    private lateinit var libTitle: TextView
    private lateinit var libGrid: RecyclerView
    private lateinit var libAdapter: TileAdapter
    private lateinit var libTabs: LinearLayout
    private val tabViews = ArrayList<Pair<String, TextView>>()
    private lateinit var libHint: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = AppRepository(this)
        prefs = Prefs(this)
        buildViews()
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        tick()
        reloadApps()
        if (System.currentTimeMillis() - lastWeatherAt > 20 * 60_000) refreshWeather()
        applyTheme(force = true)
        if (playingFile != null) video.start()
    }

    override fun onPause() {
        super.onPause()
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

        video = VideoView(this).apply { isFocusable = false }
        video.setOnPreparedListener { mp ->
            mp.isLooping = true
            mp.setVolume(0f, 0f)
            video.animate().alpha(1f).setDuration(900).start()
        }
        video.setOnErrorListener { _, _, _ -> playingFile = null; true }
        video.alpha = 0f
        root.addView(video, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))

        scrim = View(this)
        root.addView(scrim, FrameLayout.LayoutParams(-1, -1))

        // Clock + weather, top-left
        val clockBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
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

        // Brand + mode switch, top-right
        val topRight = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
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
        dock.addView(dockButton(R.drawable.ic_livetv, "Live TV") {
            Toast.makeText(this, "The Live TV guide arrives in the next update", Toast.LENGTH_SHORT).show()
        })
        dock.addView(dockButton(R.drawable.ic_apps, "Apps") { openLibrary() })
        root.addView(dock, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dpi(38f)
        })

        buildLibrary()
    }

    private fun dockButton(icon: Int, label: String, action: () -> Unit): View {
        val size = 72f
        val pad = 36f
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
            v.translationZ = if (f) dp(8f) else 0f
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

    private fun buildLibrary() {
        library = FrameLayout(this).apply { visibility = View.GONE; clipChildren = false }
        libDim = View(this)
        library.addView(libDim, FrameLayout.LayoutParams(-1, -1))

        libTitle = TextView(this).apply { text = "Apps"; style(32f, Fonts.display(this@MainActivity, 400), Color.WHITE, -0.02f) }
        library.addView(libTitle, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            leftMargin = dpi(70f); topMargin = dpi(42f)
        })

        libTabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(3f), dpi(3f), dpi(3f), dpi(3f))
        }
        for ((key, label) in listOf("all" to "All", "favorites" to "Favorites", "hidden" to "Hidden")) {
            val t = TextView(this).apply {
                text = label; gravity = Gravity.CENTER; isFocusable = true
                style(11f, Fonts.body(this@MainActivity, 700), Color.WHITE)
                setPadding(dpi(15f), 0, dpi(15f), 0)
                setOnClickListener { libraryTab = key; renderLibrary(); styleTabs() }
                setOnFocusChangeListener { _, _ -> styleTabs() }
            }
            tabViews += key to t
            libTabs.addView(t, LinearLayout.LayoutParams(-2, dpi(30f)))
        }
        library.addView(libTabs, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            rightMargin = dpi(70f); topMargin = dpi(44f)
        })

        libAdapter = TileAdapter(repo, 146f, 82f, 18f, 18f, ::open, ::showAppMenu)
        libGrid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, 5)
            adapter = libAdapter; itemAnimator = null
            clipChildren = false; clipToPadding = false; overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(0, dpi(12f), 0, dpi(60f))
        }
        library.addView(libGrid, FrameLayout.LayoutParams(-2, -1, Gravity.CENTER_HORIZONTAL).apply { topMargin = dpi(96f) })

        libHint = TextView(this).apply {
            text = "☰  Pin, reorder or hide"; gravity = Gravity.CENTER
            style(10f, Fonts.body(this@MainActivity, 600), Color.argb(215, 255, 255, 255))
            setPadding(dpi(13f), 0, dpi(13f), 0)
        }
        library.addView(libHint, FrameLayout.LayoutParams(-2, dpi(28f), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dpi(24f)
        })
        root.addView(library, FrameLayout.LayoutParams(-1, -1))
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
        clockText.setShadowLayer(dp(24f), 0f, dp(3f), if (dark) Color.argb(64, 0, 0, 0) else Color.TRANSPARENT)
        weatherIcon.setColorFilter(ink)

        dockGlass.dark = dark; dockGlass.rebuild()
        dockDivider.setBackgroundColor(if (dark) Color.argb(56, 255, 255, 255) else Color.argb(40, 11, 23, 38))
        for ((face, img) in dockButtons) { img.setColorFilter(ink); styleDockButton(face, (face.parent as View).isFocused) }
        favAdapter.dark = dark
        libAdapter.dark = dark
        libTitle.setTextColor(ink)
        libDim.setBackgroundColor(if (dark) Color.argb(160, 4, 6, 18) else Color.argb(140, 236, 242, 248))
        libHint.setTextColor(if (dark) Color.argb(215, 255, 255, 255) else Hearth.INK_DARK)
        libHint.background = LayerDrawable(arrayOf(GlassDrawable(this, 14f, dark)))
        libTabs.background = GlassDrawable(this, 18f, dark)
        modeBar.background = GlassDrawable(this, 19f, dark)
        styleModeBar(); styleTabs()

        if (changed || force) {
            worker.execute {
                val status = Display.applyBacklight(this, dark)
                android.util.Log.i("HearthDisplay", status)
            }
        }
        loadBackground()
    }

    private fun styleModeBar() {
        val ink = if (dark) Color.WHITE else Hearth.INK_DARK
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

    private fun styleTabs() {
        val ink = if (dark) Color.WHITE else Hearth.INK_DARK
        for ((key, t) in tabViews) {
            val selected = key == libraryTab
            t.background = when {
                t.isFocused -> LayerDrawable(arrayOf(hearthGradient(this, 15f, 0.5f), rimDrawable(this, 15f, true, dark)))
                selected -> GradientDrawable().apply { cornerRadius = dp(15f); setColor(if (dark) Color.argb(56, 255, 255, 255) else Color.WHITE) }
                else -> null
            }
            t.setTextColor(if (t.isFocused) Color.WHITE else if (selected) ink else Color.argb(170, Color.red(ink), Color.green(ink), Color.blue(ink)))
        }
    }

    private fun loadBackground() {
        val wantDark = dark
        worker.execute {
            val f = Backgrounds.todaysClip(this, wantDark) ?: return@execute
            ui.post {
                if (f == playingFile) return@post
                playingFile = f
                video.alpha = 0f
                video.setVideoURI(Uri.fromFile(f))
                video.start()
            }
        }
    }

    // ------------------------------------------------------------------ clock & weather

    private fun tick() {
        val now = Date()
        clockText.text = SimpleDateFormat("h:mm", Locale.US).format(now)
        dateText.text = SimpleDateFormat("EEEE, MMMM d", Locale.US).format(now)
        applyTheme()
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
                if (library.visibility == View.VISIBLE) renderLibrary()
                if (!firstFocusDone) {
                    firstFocusDone = true
                    favRow.post { favRow.getChildAt(0)?.requestFocus() ?: dock.getChildAt(2)?.requestFocus() }
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
        val cols = 5
        libGrid.layoutParams = (libGrid.layoutParams as FrameLayout.LayoutParams).apply {
            width = dpi((146f + 18f) * cols)
        }
    }

    private fun openLibrary() {
        libraryTab = "all"
        renderLibrary(); styleTabs()
        library.alpha = 0f
        library.visibility = View.VISIBLE
        library.animate().alpha(1f).setDuration(220).start()
        dock.animate().alpha(0f).setDuration(160).start()
        libGrid.post { libGrid.getChildAt(0)?.requestFocus() }
    }

    private fun closeLibrary() {
        library.animate().alpha(0f).setDuration(180).withEndAction { library.visibility = View.GONE }.start()
        dock.animate().alpha(1f).setDuration(200).start()
        dock.post { (dock.getChildAt(dock.childCount - 1)).requestFocus() }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (library.visibility == View.VISIBLE) closeLibrary()
        else favRow.getChildAt(0)?.requestFocus()
    }

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
        if (library.visibility == View.VISIBLE) renderLibrary()
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
                    if (found.isEmpty()) "Backlight: no control found" else "Backlight: ${found.first().key}${if (write) " ✓" else " (needs setup)"}") {}
                lines += GlassMenu.Item(R.drawable.ic_info, "Version ${packageManager.getPackageInfo(packageName, 0).versionName}") {}
                GlassMenu(this, dark, "Hearth setup", "This TV", null, lines).show()
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    @Suppress("unused")
    private fun ViewGroup.firstFocusable(): View? = (0 until childCount).map { getChildAt(it) }.firstOrNull { it.isFocusable }
}
