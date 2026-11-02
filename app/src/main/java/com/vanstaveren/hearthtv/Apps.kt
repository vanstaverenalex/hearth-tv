package com.vanstaveren.hearthtv

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable

/** One launchable thing on the home screen. Special tiles use a package name starting with "__". */
data class AppEntry(
    val pkg: String,
    val component: ComponentName?,
    val label: String,
    val isTvApp: Boolean,
) {
    val isSpecial get() = pkg.startsWith("__")
}

object Specials {
    const val SETTINGS = "__settings__"
    const val FIRE_HOME = "__firehome__"
}

/** Finds installed apps and caches their artwork so scrolling stays smooth. */
class AppRepository(private val context: Context) {

    private val pm = context.packageManager
    /** Wide TV banner art, only when it's real 16:9-ish artwork. */
    val banners = HashMap<String, Drawable?>()
    /** Sharpest available app icon. */
    val icons = HashMap<String, Drawable?>()
    /** A pleasant tint taken from the icon, for tiles without banner art. */
    val tints = HashMap<String, Int>()

    fun load(): List<AppEntry> {
        val found = LinkedHashMap<String, AppEntry>()
        val categories = listOf(
            Intent.CATEGORY_LEANBACK_LAUNCHER to true,
            Intent.CATEGORY_LAUNCHER to false,
        )
        for ((category, isTv) in categories) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(category)
            val results = try { pm.queryIntentActivities(query, 0) } catch (e: Exception) { emptyList() }
            for (ri in results) {
                val pkg = ri.activityInfo.packageName
                if (pkg == context.packageName || found.containsKey(pkg)) continue
                val label = ri.loadLabel(pm)?.toString()?.trim().orEmpty().ifEmpty { pkg }
                found[pkg] = AppEntry(pkg, ComponentName(pkg, ri.activityInfo.name), label, isTv)
            }
        }
        for (app in found.values) {
            if (!banners.containsKey(app.pkg)) {
                val b = try {
                    pm.getApplicationBanner(app.pkg) ?: app.component?.let { pm.getActivityBanner(it) }
                } catch (e: Exception) { null }
                // Square or tiny "banners" look broken when stretched; treat them as missing.
                val ok = b != null && b.intrinsicWidth >= 160 && b.intrinsicHeight > 0 &&
                    b.intrinsicWidth.toFloat() / b.intrinsicHeight >= 1.45f
                banners[app.pkg] = if (ok) b else null
            }
            if (!icons.containsKey(app.pkg)) {
                val ic = sharpIcon(app.pkg)
                icons[app.pkg] = ic
                tints[app.pkg] = tintFrom(ic)
            }
        }
        return found.values.sortedBy { it.label.lowercase() }
    }

    /** Ask the app for its highest-density icon so it stays crisp on a 4K TV. */
    private fun sharpIcon(pkg: String): Drawable? = try {
        val ai = pm.getApplicationInfo(pkg, 0)
        val res = pm.getResourcesForApplication(ai)
        (if (ai.icon != 0) try {
            res.getDrawableForDensity(ai.icon, android.util.DisplayMetrics.DENSITY_XXXHIGH, null)
        } catch (e: Exception) { null } else null) ?: pm.getApplicationIcon(ai)
    } catch (e: Exception) { null }

    private fun tintFrom(d: Drawable?): Int {
        if (d == null) return 0xFF2A2440.toInt()
        return try {
            val bmp = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(bmp)
            d.setBounds(0, 0, 8, 8); d.draw(c)
            var r = 0L; var g = 0L; var b = 0L; var n = 0L
            for (x in 0 until 8) for (y in 0 until 8) {
                val p = bmp.getPixel(x, y)
                val a = android.graphics.Color.alpha(p)
                if (a < 128) continue
                val rr = android.graphics.Color.red(p); val gg = android.graphics.Color.green(p); val bb = android.graphics.Color.blue(p)
                if (maxOf(rr, gg, bb) - minOf(rr, gg, bb) < 18 && (rr > 225 || rr < 25)) continue // skip white/black
                r += rr; g += gg; b += bb; n++
            }
            if (n == 0L) return 0xFF2A2440.toInt()
            val hsv = FloatArray(3)
            android.graphics.Color.RGBToHSV((r / n).toInt(), (g / n).toInt(), (b / n).toInt(), hsv)
            hsv[1] = hsv[1].coerceIn(0.35f, 0.75f)
            hsv[2] = 0.42f
            android.graphics.Color.HSVToColor(hsv)
        } catch (e: Exception) { 0xFF2A2440.toInt() }
    }

    fun launchIntent(app: AppEntry): Intent? {
        val intent = (if (app.isTvApp) pm.getLeanbackLaunchIntentForPackage(app.pkg) else null)
            ?: pm.getLaunchIntentForPackage(app.pkg)
            ?: app.component?.let { Intent(Intent.ACTION_MAIN).setComponent(it) }
        return intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/** Remembers favorites (in order) and hidden apps on this TV. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("hearth", Context.MODE_PRIVATE)

    var favorites: MutableList<String>
        get() = sp.getString("favorites", "")!!.split(",").filter { it.isNotBlank() }.toMutableList()
        set(value) { sp.edit().putString("favorites", value.joinToString(",")).apply() }

    var hidden: MutableSet<String>
        get() = sp.getStringSet("hidden", emptySet())!!.toMutableSet()
        set(value) { sp.edit().putStringSet("hidden", value).apply() }

    var seeded: Boolean
        get() = sp.getBoolean("seeded", false)
        set(value) { sp.edit().putBoolean("seeded", value).apply() }

    /** Theme: "dark", "light" or "auto". */
    var mode: String
        get() = sp.getString("mode", "auto")!!
        set(value) { sp.edit().putString("mode", value).apply() }

    /** First run: pin the family's main apps first, then other common ones. */
    fun seedFavorites(installed: Collection<String>) {
        if (seeded) return
        val common = listOf(
            "com.hulu.plus",                     // Hulu
            "com.disney.disneyplus",             // Disney+
            "com.tubitv",                        // Tubi
            "com.amazon.firetv.youtube",         // YouTube
            "com.google.android.youtube.tv",     // YouTube (Android TV build)
            "com.netflix.ninja",                 // Netflix
            "com.wbd.stream",                    // Max
            "com.amazon.avod",                   // Prime Video
        )
        favorites = common.filter { it in installed }.take(5).toMutableList()
        seeded = true
    }
}
