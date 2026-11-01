package com.vanstaveren.hearthtv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var repo: AppRepository
    private lateinit var prefs: Prefs
    private lateinit var favAdapter: AppAdapter
    private lateinit var gridAdapter: AppAdapter

    private lateinit var greeting: TextView
    private lateinit var clock: TextView
    private lateinit var ampm: TextView
    private lateinit var date: TextView
    private lateinit var weatherBox: View
    private lateinit var weatherIcon: TextView
    private lateinit var weatherTemp: TextView
    private lateinit var weatherDesc: TextView
    private lateinit var weatherRange: TextView
    private lateinit var favRow: RecyclerView
    private lateinit var favLabel: TextView
    private lateinit var grid: RecyclerView

    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var allApps: List<AppEntry> = emptyList()
    private var lastWeatherAt = 0L
    private var firstLoad = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        repo = AppRepository(this)
        prefs = Prefs(this)

        greeting = findViewById(R.id.greeting)
        clock = findViewById(R.id.clock)
        ampm = findViewById(R.id.ampm)
        date = findViewById(R.id.date)
        weatherBox = findViewById(R.id.weatherBox)
        weatherIcon = findViewById(R.id.weatherIcon)
        weatherTemp = findViewById(R.id.weatherTemp)
        weatherDesc = findViewById(R.id.weatherDesc)
        weatherRange = findViewById(R.id.weatherRange)
        favRow = findViewById(R.id.favRow)
        favLabel = findViewById(R.id.favLabel)
        grid = findViewById(R.id.grid)

        favAdapter = AppAdapter(repo, large = true, onOpen = ::open, onMenu = ::showMenu)
        gridAdapter = AppAdapter(repo, large = false, onOpen = ::open, onMenu = ::showMenu)

        favRow.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        favRow.adapter = favAdapter
        favRow.itemAnimator = null

        grid.layoutManager = GridLayoutManager(this, 5)
        grid.adapter = gridAdapter
        grid.itemAnimator = null
    }

    override fun onResume() {
        super.onResume()
        tickClock()
        reloadApps()
        if (System.currentTimeMillis() - lastWeatherAt > 20 * 60_000) refreshWeather()
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacksAndMessages(null)
    }

    // The launcher is "home" — Back shouldn't leave it.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (favAdapter.itemCount > 0) favRow.getChildAt(0)?.findViewById<View>(R.id.tile)?.requestFocus()
    }

    // ---------- Clock ----------

    private fun tickClock() {
        val now = Date()
        clock.text = SimpleDateFormat("h:mm", Locale.US).format(now)
        ampm.text = SimpleDateFormat("a", Locale.US).format(now)
        date.text = SimpleDateFormat("EEEE, MMMM d", Locale.US).format(now)
        greeting.text = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 5..11 -> "Good morning"
            in 12..16 -> "Good afternoon"
            in 17..21 -> "Good evening"
            else -> "Good night"
        }
        val msToNextMinute = 60_000 - (System.currentTimeMillis() % 60_000) + 50
        ui.postDelayed(::tickClock, msToNextMinute)
    }

    // ---------- Weather ----------

    private fun refreshWeather() {
        worker.execute {
            val w = Weather.fetch()
            ui.post {
                if (w != null) {
                    lastWeatherAt = System.currentTimeMillis()
                    val (emoji, words) = Weather.describe(w.code, w.isDay)
                    weatherIcon.text = emoji
                    weatherTemp.text = "${w.temp}°"
                    weatherDesc.text = words
                    weatherRange.text = "H ${w.high}°  ·  L ${w.low}°"
                    weatherBox.visibility = View.VISIBLE
                }
                ui.postDelayed(::refreshWeather, 30 * 60_000L)
            }
        }
    }

    // ---------- Apps ----------

    private fun reloadApps() {
        worker.execute {
            val apps = repo.load()
            ui.post {
                allApps = apps
                prefs.seedFavorites(apps.map { it.pkg })
                render()
                if (firstLoad) {
                    firstLoad = false
                    focusFirst()
                }
            }
        }
    }

    private fun render() {
        val byPkg = allApps.associateBy { it.pkg }
        val hidden = prefs.hidden
        val favs = prefs.favorites.mapNotNull { byPkg[it] }

        favAdapter.items = favs
        val showFavs = favs.isNotEmpty()
        favRow.visibility = if (showFavs) View.VISIBLE else View.GONE
        favLabel.visibility = if (showFavs) View.VISIBLE else View.GONE

        val rest = allApps.filter { it.pkg !in hidden }.toMutableList()
        rest += AppEntry(Specials.SETTINGS, null, "Settings", false)
        if (hidden.isNotEmpty()) rest += AppEntry(Specials.HIDDEN, null, "Hidden apps (${hidden.size})", false)
        gridAdapter.items = rest
    }

    private fun focusFirst() {
        val target = if (favAdapter.itemCount > 0) favRow else grid
        target.post { target.getChildAt(0)?.findViewById<View>(R.id.tile)?.requestFocus() }
    }

    private fun open(app: AppEntry) {
        when (app.pkg) {
            Specials.SETTINGS -> safeStart(Intent(Settings.ACTION_SETTINGS))
            Specials.HIDDEN -> showHiddenApps()
            else -> {
                val intent = repo.launchIntent(app)
                if (intent == null) toast("Can't open ${app.label}") else safeStart(intent)
            }
        }
    }

    private fun safeStart(intent: Intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            toast("Couldn't open that")
        }
    }

    // ---------- Menu (☰ button or long-press) ----------

    private fun showMenu(app: AppEntry) {
        if (app.isSpecial) return
        val favs = prefs.favorites
        val index = favs.indexOf(app.pkg)
        val isFav = index >= 0

        val options = mutableListOf<Pair<String, () -> Unit>>()
        if (isFav) {
            if (index > 0) options += "Move left" to { move(app.pkg, -1) }
            if (index < favs.size - 1) options += "Move right" to { move(app.pkg, +1) }
            options += "Remove from favorites" to {
                prefs.favorites = favs.apply { remove(app.pkg) }; render()
            }
        } else {
            options += "Add to favorites" to {
                prefs.favorites = favs.apply { add(app.pkg) }; render()
            }
        }
        options += "Hide from All apps" to {
            prefs.hidden = prefs.hidden.apply { add(app.pkg) }; render()
        }
        options += "App info" to {
            safeStart(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}"))
            )
        }

        AlertDialog.Builder(this, R.style.HearthDialog)
            .setTitle(app.label)
            .setItems(options.map { it.first }.toTypedArray()) { _, which -> options[which].second() }
            .show()
    }

    private fun move(pkg: String, delta: Int) {
        val favs = prefs.favorites
        val i = favs.indexOf(pkg)
        val j = i + delta
        if (i < 0 || j !in favs.indices) return
        favs.removeAt(i)
        favs.add(j, pkg)
        prefs.favorites = favs
        render()
        favRow.post {
            favRow.scrollToPosition(j)
            favRow.post { favRow.layoutManager?.findViewByPosition(j)?.findViewById<View>(R.id.tile)?.requestFocus() }
        }
    }

    private fun showHiddenApps() {
        val hidden = prefs.hidden.toList()
        if (hidden.isEmpty()) return
        val byPkg = allApps.associateBy { it.pkg }
        val labels = hidden.map { byPkg[it]?.label ?: it }.toTypedArray()
        AlertDialog.Builder(this, R.style.HearthDialog)
            .setTitle("Tap an app to show it again")
            .setItems(labels) { _, which ->
                prefs.hidden = prefs.hidden.apply { remove(hidden[which]) }
                render()
            }
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
