package com.vanstaveren.hearthtv

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import java.util.Calendar

/**
 * Light/dark mode and the TV backlight.
 *
 * Android has no standard backlight API for TVs; TCL/Toshiba keep it in their own
 * picture settings. Some TVs expose it as a hidden setting (e.g. "picture_backlight").
 * We scan for any backlight-like setting and, if the app was granted
 * WRITE_SECURE_SETTINGS during setup, write 0 (dark) or 100 (light) to it.
 */
object Display {
    private const val TAG = "HearthDisplay"
    private val pattern = Regex("(?i)backlight|picture.*bright|panel.*bright")

    data class Found(val table: String, val key: String, val value: String?)

    fun isDark(prefs: Prefs, weather: WeatherNow?): Boolean = when (prefs.mode) {
        "dark" -> true
        "light" -> false
        else -> {
            val now = System.currentTimeMillis()
            val rise = weather?.sunriseMs
            val set = weather?.sunsetMs
            if (rise != null && set != null) now < rise || now > set
            else Calendar.getInstance().get(Calendar.HOUR_OF_DAY).let { it < 7 || it >= 19 }
        }
    }

    fun canWriteSecure(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** Every setting on this TV whose name looks like a backlight control. */
    fun scan(ctx: Context): List<Found> {
        val out = ArrayList<Found>()
        val tables = listOf(
            "global" to Settings.Global.CONTENT_URI,
            "system" to Settings.System.CONTENT_URI,
            "secure" to Settings.Secure.CONTENT_URI,
        )
        for ((name, uri) in tables) {
            try {
                ctx.contentResolver.query(uri, arrayOf("name", "value"), null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val key = c.getString(0) ?: continue
                        if (pattern.containsMatchIn(key)) out += Found(name, key, c.getString(1))
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "scan $name failed: ${e.message}")
            }
        }
        // Known names some TV makers use, in case the table listing is restricted.
        for (key in listOf("picture_backlight", "backlight", "tv_picture_backlight", "picture_backlight_level")) {
            if (out.none { it.key == key }) {
                val v = try { Settings.Global.getString(ctx.contentResolver, key) } catch (e: Exception) { null }
                if (v != null) out += Found("global", key, v)
            }
        }
        out.forEach { Log.i(TAG, "found ${it.table}/${it.key}=${it.value}") }
        return out
    }

    /** Push the backlight to 0 (dark) or max (light). Returns a short status line. */
    fun applyBacklight(ctx: Context, dark: Boolean): String {
        val found = scan(ctx)
        if (found.isEmpty()) return "No backlight setting found on this TV"
        if (!canWriteSecure(ctx)) return "Backlight found (${found.first().key}); needs one-time setup permission"
        var wrote = 0
        for (f in found) {
            val target = if (dark) 0 else 100
            try {
                val ok = when (f.table) {
                    "global" -> Settings.Global.putInt(ctx.contentResolver, f.key, target)
                    "secure" -> Settings.Secure.putInt(ctx.contentResolver, f.key, target)
                    else -> Settings.System.putInt(ctx.contentResolver, f.key, target)
                }
                if (ok) wrote++
                // Nudge any app watching this setting.
                ctx.contentResolver.notifyChange(Uri.withAppendedPath(Settings.Global.CONTENT_URI, f.key), null)
                Log.i(TAG, "set ${f.table}/${f.key}=$target ok=$ok")
            } catch (e: Exception) {
                Log.w(TAG, "set ${f.key} failed: ${e.message}")
            }
        }
        return if (wrote > 0) "Backlight set to ${if (dark) "0" else "100"}" else "Couldn't change backlight"
    }

    fun homeTakeoverOn(ctx: Context): Boolean {
        val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        return enabled.contains(ctx.packageName + "/")
    }
}
