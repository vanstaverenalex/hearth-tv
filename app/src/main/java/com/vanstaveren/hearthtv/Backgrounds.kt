package com.vanstaveren.hearthtv

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Live backgrounds: one shuffled playlist of seamless 4K loops (built by the "Build seamless backgrounds"
 * GitHub Action and listed in backgrounds.json). The scene moves on by itself every so often, and the
 * ⏭ button in the light/dark pill skips straight to the next one. Upcoming clips are fetched ahead so a
 * skip is instant.
 */
object Backgrounds {
    private const val TAG = "HearthBg"
    private const val LIST_URL =
        "https://raw.githubusercontent.com/vanstaverenalex/hearth-tv/main/backgrounds.json"
    private const val KEEP = 5            // clips kept on the TV
    private const val LIST_TTL = 6 * 3600_000L

    data class Clip(val id: String, val version: Int, val url: String)

    private var cached: List<Clip> = emptyList()
    private var cachedAt = 0L

    // ---------------------------------------------------------------- list

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
                Clip(id, o.optInt("v", 1), if (base.isNotBlank()) "$base$id.mp4" else o.optString("url", o.optString("src")))
            }
        } catch (e: Exception) { Log.w(TAG, "list: ${e.message}"); cached }
        cachedAt = System.currentTimeMillis()
        return cached
    }

    // ---------------------------------------------------------------- playlist

    private fun prefs(ctx: android.content.Context) = ctx.getSharedPreferences("hearth_bg", 0)

    /** Same shuffled order on every TV for a given week, so it feels varied but never repeats back-to-back. */
    private fun playlist(ctx: android.content.Context): List<Clip> {
        val all = list(ctx)
        val week = System.currentTimeMillis() / (7 * 24 * 3600_000L)
        return all.shuffled(java.util.Random(week))
    }

    fun currentId(ctx: android.content.Context): String? = prefs(ctx).getString("current", null)

    /** The clip to show now (the saved one, or the first in the playlist). Off the main thread. */
    fun current(ctx: android.content.Context): File? {
        val pl = playlist(ctx)
        if (pl.isEmpty()) return anyLocal(ctx)
        val cur = pl.firstOrNull { it.id == currentId(ctx) } ?: pl.first()
        prefs(ctx).edit().putString("current", cur.id).apply()
        return download(ctx, cur) ?: anyLocal(ctx)
    }

    /**
     * Advance to the next clip. If it isn't on the TV yet, jump to the next one that is (instant),
     * and keep fetching ahead in the background.
     */
    fun next(ctx: android.content.Context): File? {
        val pl = playlist(ctx)
        if (pl.isEmpty()) return anyLocal(ctx)
        val i = pl.indexOfFirst { it.id == currentId(ctx) }
        val order = (1..pl.size).map { pl[(i + it).mod(pl.size)] }
        val pick = order.firstOrNull { it.id != currentId(ctx) && fileFor(ctx, it).exists() } ?: order.first()
        prefs(ctx).edit().putString("current", pick.id).apply()
        return download(ctx, pick) ?: anyLocal(ctx)
    }

    /** Quietly fetch the next couple of clips so skipping is instant. */
    fun prefetch(ctx: android.content.Context) {
        val pl = playlist(ctx)
        if (pl.isEmpty()) return
        val i = pl.indexOfFirst { it.id == currentId(ctx) }
        for (k in 1..2) download(ctx, pl[(i + k).mod(pl.size)])
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
                ?.sortedByDescending { it.lastModified() }?.drop(KEEP)
                ?.filter { !it.name.startsWith("${currentId(ctx)}.") }?.forEach { it.delete() }
            file
        } catch (e: Exception) {
            Log.w(TAG, "download ${c.id} failed: ${e.message}")
            null
        }
    }
}
