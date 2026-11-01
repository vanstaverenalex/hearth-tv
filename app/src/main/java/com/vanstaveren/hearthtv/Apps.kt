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
    val banners = HashMap<String, Drawable?>()
    val icons = HashMap<String, Drawable?>()

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
                banners[app.pkg] = try {
                    pm.getApplicationBanner(app.pkg)
                        ?: app.component?.let { pm.getActivityBanner(it) }
                } catch (e: Exception) { null }
            }
            if (!icons.containsKey(app.pkg)) {
                icons[app.pkg] = try { pm.getApplicationIcon(app.pkg) } catch (e: Exception) { null }
            }
        }
        return found.values.sortedBy { it.label.lowercase() }
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
