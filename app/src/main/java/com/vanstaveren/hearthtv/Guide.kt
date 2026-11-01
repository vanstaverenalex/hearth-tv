package com.vanstaveren.hearthtv

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Live TV guide data, stitched together from free sources:
 *  - channels.json in the GitHub repo: the Hulu Live lineup, name aliases, and Hulu watch links
 *  - TVmaze (CC BY-SA): network and cable show schedules
 *  - ESPN's public scoreboard feed: live games and which channel carries them
 * Where nothing is known, the guide shows "Live on <channel>" so it can still be tuned.
 */
object Guide {
    private const val TAG = "HearthGuide"
    private const val CHANNELS_URL =
        "https://raw.githubusercontent.com/vanstaverenalex/hearth-tv/main/channels.json"

    data class Channel(
        val id: String,
        val name: String,
        val aliases: List<String>,
        val hulu: String?,
    )

    data class Program(
        val start: Long,
        val end: Long,
        val title: String,
        val detail: String,
        val sport: Boolean = false,
        val gap: Boolean = false,
    )

    data class Lineup(val channels: List<Channel>, val programs: Map<String, List<Program>>, val loadedAt: Long)

    private fun get(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000; readTimeout = 15000
            setRequestProperty("User-Agent", "HearthTV/1.0 (family TV guide)")
        }
        return conn.inputStream.bufferedReader().use { it.readText() }.also { conn.disconnect() }
    }

    private fun norm(s: String) = s.lowercase(Locale.US).replace(Regex("[^a-z0-9]"), "")

    fun loadChannels(ctx: Context): List<Channel> {
        val cache = File(ctx.filesDir, "channels.json")
        val text = try { get(CHANNELS_URL).also { cache.writeText(it) } } catch (e: Exception) {
            if (cache.exists()) cache.readText() else return emptyList()
        }
        return try {
            val arr = JSONObject(text).getJSONArray("channels")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val al = o.optJSONArray("aliases") ?: JSONArray()
                Channel(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    aliases = (listOf(o.getString("name")) + (0 until al.length()).map { al.getString(it) }).map(::norm),
                    hulu = o.optString("hulu", "").ifBlank { null },
                )
            }
        } catch (e: Exception) { Log.w(TAG, "channels: ${e.message}"); emptyList() }
    }

    private fun channelFor(name: String, channels: List<Channel>): Channel? {
        val n = norm(name)
        if (n.isEmpty()) return null
        return channels.firstOrNull { c -> c.aliases.any { it == n } }
    }

    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
    private val isoMin = SimpleDateFormat("yyyy-MM-dd'T'HH:mmX", Locale.US)

    /** TVmaze schedule for one day (US). */
    private fun tvmaze(day: Date, channels: List<Channel>, into: MutableMap<String, MutableList<Program>>) {
        val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(day)
        val arr = try { JSONArray(get("https://api.tvmaze.com/schedule?country=US&date=$d")) } catch (e: Exception) {
            Log.w(TAG, "tvmaze $d: ${e.message}"); return
        }
        for (i in 0 until arr.length()) {
            val ep = arr.getJSONObject(i)
            val show = ep.optJSONObject("show") ?: continue
            val net = show.optJSONObject("network")?.optString("name") ?: continue
            val ch = channelFor(net, channels) ?: continue
            val start = try { iso.parse(ep.getString("airstamp"))?.time } catch (e: Exception) { null } ?: continue
            val runtime = if (ep.isNull("runtime")) show.optInt("runtime", 30) else ep.optInt("runtime", 30)
            val mins = if (runtime <= 0) 30 else runtime
            val showName = show.optString("name")
            val epName = ep.optString("name")
            val s = ep.optInt("season", 0); val n = ep.optInt("number", 0)
            val detail = buildString {
                if (s > 0 && n > 0) append("S$s E$n")
                if (epName.isNotBlank() && epName != showName) { if (isNotEmpty()) append("  ·  "); append(epName) }
            }
            into.getOrPut(ch.id) { ArrayList() } += Program(start, start + mins * 60_000L, showName, detail)
        }
    }

    private val sports = listOf(
        Triple("football", "nfl", 195), Triple("football", "college-football", 210),
        Triple("basketball", "nba", 150), Triple("basketball", "mens-college-basketball", 135),
        Triple("hockey", "nhl", 165), Triple("baseball", "mlb", 180),
    )

    /** Live and upcoming games from ESPN's public scoreboards, placed on the channel that airs them. */
    private fun espn(day: Date, channels: List<Channel>, into: MutableMap<String, MutableList<Program>>) {
        val d = SimpleDateFormat("yyyyMMdd", Locale.US).format(day)
        for ((sport, league, minutes) in sports) {
            val events = try {
                JSONObject(get("https://site.api.espn.com/apis/site/v2/sports/$sport/$league/scoreboard?dates=$d"))
                    .optJSONArray("events") ?: continue
            } catch (e: Exception) { continue }
            val leagueName = when (league) {
                "nfl" -> "NFL"; "college-football" -> "College Football"; "nba" -> "NBA"
                "mens-college-basketball" -> "College Basketball"; "nhl" -> "NHL"; else -> "MLB"
            }
            for (i in 0 until events.length()) {
                val ev = events.getJSONObject(i)
                val start = try { isoMin.parse(ev.getString("date"))?.time } catch (e: Exception) { null } ?: continue
                val comp = ev.optJSONArray("competitions")?.optJSONObject(0) ?: continue
                val names = ArrayList<String>()
                comp.optJSONArray("broadcasts")?.let { b ->
                    for (j in 0 until b.length()) b.getJSONObject(j).optJSONArray("names")?.let { n ->
                        for (k in 0 until n.length()) names += n.getString(k)
                    }
                }
                comp.optJSONArray("geoBroadcasts")?.let { g ->
                    for (j in 0 until g.length()) g.getJSONObject(j).optJSONObject("media")?.optString("shortName")?.let { names += it }
                }
                val state = ev.optJSONObject("status")?.optJSONObject("type")?.optString("state") ?: "pre"
                val detailState = ev.optJSONObject("status")?.optJSONObject("type")?.optString("shortDetail") ?: ""
                val end = if (state == "post") start + minutes * 60_000L else maxOf(start + minutes * 60_000L, System.currentTimeMillis() + 20 * 60_000L)
                val title = ev.optString("shortName").ifBlank { ev.optString("name") }
                val placed = HashSet<String>()
                for (bn in names) {
                    if (bn.contains("+")) continue // ESPN+, Paramount+ etc. are streaming-only
                    val ch = channelFor(bn, channels) ?: continue
                    if (!placed.add(ch.id)) continue
                    into.getOrPut(ch.id) { ArrayList() } +=
                        Program(start, end, "$leagueName: $title", if (state == "in") "Live now  ·  $detailState" else leagueName, sport = true)
                }
            }
        }
    }

    /** Build the full guide from now back 30 minutes to about 9 hours ahead. Call off the main thread. */
    fun load(ctx: Context): Lineup {
        val channels = loadChannels(ctx)
        val raw = HashMap<String, MutableList<Program>>()
        val cal = Calendar.getInstance()
        val today = cal.time
        cal.add(Calendar.DAY_OF_YEAR, 1)
        val tomorrow = cal.time
        cal.add(Calendar.DAY_OF_YEAR, -2)
        val yesterday = cal.time
        for (d in listOf(yesterday, today, tomorrow)) tvmaze(d, channels, raw)
        // ESPN days are US-Eastern; ask for today and tomorrow (UTC games after 8pm ET land on "tomorrow").
        for (d in listOf(today, tomorrow)) espn(d, channels, raw)

        val now = System.currentTimeMillis()
        val from = now - 60 * 60_000L
        val to = now + 10 * 3600_000L
        val out = HashMap<String, List<Program>>()
        for (ch in channels) {
            val list = (raw[ch.id] ?: ArrayList()).filter { it.end > from && it.start < to }
            // Games win over regular listings in the same slot; drop exact duplicates.
            val sportsList = list.filter { it.sport }.sortedBy { it.start }.distinctBy { it.start to it.title }
            val shows = list.filter { !it.sport }.filter { p -> sportsList.none { s -> p.start < s.end && p.end > s.start } }
            val merged = (sportsList + shows).sortedBy { it.start }.distinctBy { it.start }
            // Trim overlaps and fill holes with "Live on <channel>".
            val filled = ArrayList<Program>()
            var t = from
            for (p in merged) {
                val s = maxOf(p.start, t)
                if (s - t >= 5 * 60_000L) filled += Program(t, s, "Live on ${ch.name}", "", gap = true)
                if (p.end > s) filled += p.copy(start = s)
                t = maxOf(t, p.end)
            }
            if (to - t > 0) filled += Program(t, to, "Live on ${ch.name}", "", gap = true)
            out[ch.id] = filled
        }
        Log.i(TAG, "guide: ${channels.size} channels, ${raw.values.sumOf { it.size }} raw listings")
        return Lineup(channels, out, now)
    }

    @Suppress("unused")
    private val utc = TimeZone.getTimeZone("UTC")
}
