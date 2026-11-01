package com.vanstaveren.hearthtv

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar

/**
 * Live nature backgrounds. The list lives in backgrounds.json in the GitHub repo,
 * so clips can be added or swapped without updating the app. Each TV downloads a
 * clip once, keeps at most two on disk, and rotates to a new clip each day.
 */
object Backgrounds {
    private const val TAG = "HearthBg"
    private const val LIST_URL =
        "https://raw.githubusercontent.com/vanstaverenalex/hearth-tv/main/backgrounds.json"
    private const val KEEP = 2

    data class Clip(val id: String, val url: String, val mood: String)

    private fun fetchList(ctx: Context): List<Clip> {
        val cache = File(ctx.filesDir, "backgrounds.json")
        val text = try {
            val conn = (URL(LIST_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000; readTimeout = 8000
                setRequestProperty("User-Agent", "HearthTV/1.0")
            }
            conn.inputStream.bufferedReader().use { it.readText() }.also { cache.writeText(it) }
        } catch (e: Exception) {
            if (cache.exists()) cache.readText() else return emptyList()
        }
        return try {
            val arr = JSONObject(text).getJSONArray("clips")
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Clip(o.getString("id"), o.getString("url"), o.optString("mood", "any"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Returns a ready-to-play local file for today, downloading it if needed. Call off the main thread. */
    fun todaysClip(ctx: Context, dark: Boolean): File? {
        val dir = File(ctx.filesDir, "bg").apply { mkdirs() }
        val list = fetchList(ctx)
        val pool = list.filter { it.mood == "any" || it.mood == (if (dark) "night" else "day") }.ifEmpty { list }
        if (pool.isEmpty()) return dir.listFiles()?.firstOrNull { it.name.endsWith(".mp4") }

        val day = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        val clip = pool[day % pool.size]
        val file = File(dir, "${clip.id}.mp4")
        if (file.exists() && file.length() > 0) return file

        return try {
            val tmp = File(dir, "${clip.id}.part")
            val conn = (URL(clip.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000; readTimeout = 30000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "HearthTV/1.0")
            }
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it, 1 shl 16) } }
            conn.disconnect()
            tmp.renameTo(file)
            // Keep storage in check: only the newest few clips stay.
            dir.listFiles()?.filter { it.name.endsWith(".mp4") }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(KEEP)?.forEach { it.delete() }
            file
        } catch (e: Exception) {
            Log.w(TAG, "download failed: ${e.message}")
            dir.listFiles()?.firstOrNull { it.name.endsWith(".mp4") }
        }
    }
}
