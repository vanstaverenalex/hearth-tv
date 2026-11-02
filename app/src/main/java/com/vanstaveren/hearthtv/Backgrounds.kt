package com.vanstaveren.hearthtv

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar

/**
 * Live backgrounds that fit the moment at home: light/dark mode, time of day
 * (dawn, day, golden hour, night from Kalamazoo's real sunrise/sunset), the season,
 * and the current weather. Clips are seamless 4K loops built by the
 * "Build seamless backgrounds" GitHub Action and listed in backgrounds.json.
 */
object Backgrounds {
    private const val TAG = "HearthBg"
    private const val LIST_URL =
        "https://raw.githubusercontent.com/vanstaverenalex/hearth-tv/main/backgrounds.json"
    private const val KEEP = 5            // clips kept on the TV (~100 MB each at most)
    private const val LIST_TTL = 6 * 3600_000L

    data class Clip(
        val id: String,
        val version: Int,
        val url: String,
        val times: Set<String>,
        val seasons: Set<String>,
        val weather: Set<String>,
        val kind: String,
    )

    data class Scene(val time: String, val season: String, val weather: String) {
        val key get() = "$time|$season|$weather"
        override fun toString() = key
    }

    private var cached: List<Clip> = emptyList()
    private var cachedAt = 0L

    // ---------------------------------------------------------------- context

    fun season(): String = when (Calendar.getInstance().get(Calendar.MONTH)) {
        Calendar.DECEMBER, Calendar.JANUARY, Calendar.FEBRUARY -> "winter"
        Calendar.MARCH, Calendar.APRIL, Calendar.MAY -> "spring"
        Calendar.JUNE, Calendar.JULY, Calendar.AUGUST -> "summer"
        else -> "fall"
    }

    fun weatherKind(code: Int?): String = when (code) {
        null -> "clear"
        0, 1 -> "clear"
        2, 3 -> "cloudy"
        45, 48 -> "fog"
        in 51..67, 80, 81, 82 -> "rain"
        in 71..77, 85, 86 -> "snow"
        95, 96, 99 -> "storm"
        else -> "cloudy"
    }

    fun timeOfDay(w: WeatherNow?, now: Long = System.currentTimeMillis()): String {
        val rise = w?.sunriseMs
        val set = w?.sunsetMs
        if (rise != null && set != null) {
            return when {
                now < rise - 30 * 60_000L -> "night"
                now < rise + 90 * 60_000L -> "dawn"
                now < set - 90 * 60_000L -> "day"
                now < set + 25 * 60_000L -> "golden"
                else -> "night"
            }
        }
        return when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 6..8 -> "dawn"; in 9..16 -> "day"; in 17..18 -> "golden"; else -> "night"
        }
    }

    /** What the scene should feel like right now. Dark mode by day leans cozy; light mode at night leans bright. */
    fun contextNow(dark: Boolean, w: WeatherNow?): Scene {
        var t = timeOfDay(w)
        if (dark && (t == "day" || t == "dawn")) t = "night"
        if (!dark && t == "night") t = "day"
        return Scene(t, season(), weatherKind(w?.code))
    }

    // ---------------------------------------------------------------- list

    private fun set(a: JSONArray?): Set<String> =
        if (a == null) setOf("any") else (0 until a.length()).map { a.getString(it) }.toSet()

    private fun list(ctx: android.content.Context): List<Clip> {
        if (cached.isNotEmpty() && System.currentTimeMillis() - cachedAt < LIST_TTL) return cached
        val cache = File(ctx.filesDir, "backgrounds.json")
        val text = try {
            val conn = (URL(LIST_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000; readTimeout = 8000
                setRequestProperty("User-Agent", "HearthTV/1.0")
            }
            conn.inputStream.bufferedReader().use { it.readText() }.also { cache.writeText(it) }
        } catch (e: Exception) {
            if (cache.exists()) cache.readText() else return cached
        }
        cached = try {
            val root = JSONObject(text)
            val base = root.optString("release", "")
            val arr = root.getJSONArray("clips")
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                val id = o.getString("id")
                Clip(
                    id = id,
                    version = o.optInt("v", 1),
                    url = if (base.isNotBlank()) "$base$id.mp4" else o.optString("url", o.optString("src")),
                    times = set(o.optJSONArray("times")),
                    seasons = set(o.optJSONArray("seasons")),
                    weather = set(o.optJSONArray("weather")),
                    kind = o.optString("kind", "nature"),
                )
            }
        } catch (e: Exception) { Log.w(TAG, "list: ${e.message}"); cached }
        cachedAt = System.currentTimeMillis()
        return cached
    }

    // ---------------------------------------------------------------- choice

    private fun score(c: Clip, cx: Scene): Int {
        if (cx.time !in c.times) return -1
        val weatherOk = "any" in c.weather || cx.weather in c.weather
        // Rain/snow/storm clips only appear when it's actually doing that outside.
        val specialWeather = c.weather.all { it in setOf("rain", "snow", "storm") }
        if (specialWeather && cx.weather !in c.weather) return -1
        var s = 10
        s += when {
            cx.weather in c.weather -> 6
            "any" in c.weather -> 2
            weatherOk -> 2
            else -> -3
        }
        s += when {
            cx.season in c.seasons -> 4
            "any" in c.seasons -> 1
            else -> -6
        }
        return s
    }

    /** Best few clips for this moment, most fitting first. */
    fun candidates(ctx: android.content.Context, cx: Scene): List<Clip> {
        val all = list(ctx)
        val scored = all.map { it to score(it, cx) }.filter { it.second >= 0 }.sortedByDescending { it.second }
        if (scored.isEmpty()) return all.filter { cx.time in it.times }.ifEmpty { all }
        val best = scored.first().second
        return scored.filter { it.second >= best - 2 }.map { it.first }
    }

    /** Picks today's clip for this moment (varies day to day), downloading it if needed. Off the main thread. */
    fun pick(ctx: android.content.Context, cx: Scene): File? {
        val pool = candidates(ctx, cx)
        if (pool.isEmpty()) return anyLocal(ctx)
        val day = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        val rotated = pool.indices.map { pool[(it + day + cx.key.hashCode().mod(pool.size)) % pool.size] }
        // Prefer something already on the TV so the scene changes instantly; fetch the ideal one for next time.
        val ideal = rotated.first()
        val local = rotated.firstOrNull { fileFor(ctx, it).exists() }
        if (local != null && local != ideal) {
            Thread { download(ctx, ideal) }.start()
            return fileFor(ctx, local)
        }
        return download(ctx, ideal) ?: anyLocal(ctx)
    }

    /** Quietly fetch the clip we'll want at the next part of the day. */
    fun prefetchNext(ctx: android.content.Context, cx: Scene) {
        val next = when (cx.time) { "dawn" -> "day"; "day" -> "golden"; "golden" -> "night"; else -> "dawn" }
        val pool = candidates(ctx, cx.copy(time = next))
        pool.firstOrNull()?.let { download(ctx, it) }
    }

    private fun dir(ctx: android.content.Context) = File(ctx.filesDir, "bg").apply { mkdirs() }
    private fun fileFor(ctx: android.content.Context, c: Clip) = File(dir(ctx), "${c.id}.v${c.version}.mp4")
    private fun anyLocal(ctx: android.content.Context) =
        dir(ctx).listFiles()?.filter { it.name.endsWith(".mp4") }?.maxByOrNull { it.lastModified() }

    @Synchronized
    private fun download(ctx: android.content.Context, c: Clip): File? {
        val file = fileFor(ctx, c)
        if (file.exists() && file.length() > 0) { file.setLastModified(System.currentTimeMillis()); return file }
        return try {
            val tmp = File(dir(ctx), "${c.id}.part")
            val conn = (URL(c.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000; readTimeout = 30000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "HearthTV/1.0")
            }
            if (conn.responseCode !in 200..299) { Log.w(TAG, "${c.id}: HTTP ${conn.responseCode}"); return null }
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 16) } }
            conn.disconnect()
            tmp.renameTo(file)
            Log.i(TAG, "downloaded ${c.id} (${file.length() / 1_000_000} MB)")
            dir(ctx).listFiles()?.filter { it.name.endsWith(".mp4") }
                ?.sortedByDescending { it.lastModified() }?.drop(KEEP)?.forEach { it.delete() }
            file
        } catch (e: Exception) {
            Log.w(TAG, "download ${c.id} failed: ${e.message}")
            null
        }
    }
}
