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
        val category: String,
        val logo: String?,
        val gn: List<String> = emptyList(),
    )

    data class GnLineup(val lineupId: String, val headendId: String, val postalCode: String, val device: String)
    @Volatile private var gnLineup: GnLineup? = null

    data class Category(val id: String, val name: String)

    @Volatile var categories: List<Category> = emptyList()

    data class Program(
        val start: Long,
        val end: Long,
        val title: String,
        val detail: String,
        val sport: Boolean = false,
        val gap: Boolean = false,
        val isNew: Boolean = false,
        val isLive: Boolean = false,
        val desc: String = "",
        val rich: Boolean = false,   // came from Gracenote (best data)
    )

    data class Lineup(
        val channels: List<Channel>, val programs: Map<String, List<Program>>, val loadedAt: Long,
        /** Human-readable source summary, e.g. "Gracenote · 61 of 68 channels". */
        val source: String = "",
    )

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
            val root = JSONObject(text)
            val logoBase = root.optString("logos", "")
            root.optJSONObject("gracenote")?.let { g ->
                gnLineup = GnLineup(g.getString("lineupId"), g.getString("headendId"), g.getString("postalCode"), g.optString("device", "X"))
            }
            root.optJSONArray("categories")?.let { c ->
                categories = (0 until c.length()).map { Category(c.getJSONObject(it).getString("id"), c.getJSONObject(it).getString("name")) }
            }
            val arr = root.getJSONArray("channels")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val al = o.optJSONArray("aliases") ?: JSONArray()
                Channel(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    aliases = (listOf(o.getString("name")) + (0 until al.length()).map { al.getString(it) }).map(::norm),
                    hulu = o.optString("hulu", "").ifBlank { null },
                    category = o.optString("category", "entertainment"),
                    logo = if (logoBase.isNotBlank()) logoBase + o.getString("id") else null,
                    gn = o.optJSONArray("gn")?.let { g -> (0 until g.length()).map { g.getString(it) } } ?: emptyList(),
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
        Triple("basketball", "wnba", 120), Triple("basketball", "womens-college-basketball", 120),
        Triple("soccer", "usa.1", 120), Triple("soccer", "eng.1", 120), Triple("golf", "pga", 300),
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
                "mens-college-basketball" -> "College Basketball"; "nhl" -> "NHL"; "mlb" -> "MLB"
                "wnba" -> "WNBA"; "womens-college-basketball" -> "Women's College Basketball"
                "usa.1" -> "MLS"; "eng.1" -> "Premier League"; else -> "Golf"
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
    private val isoSec = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX", Locale.US)
    private val isoMin = SimpleDateFormat("yyyy-MM-dd'T'HH:mmX", Locale.US)
    /** Gracenote times look like 2026-11-02T02:00:00Z (older responses dropped the seconds). */
    private fun parseIso(t: String): Long? =
        try { isoSec.parse(t)?.time } catch (_: Exception) { null } ?: try { isoMin.parse(t)?.time } catch (_: Exception) { null }

    /**
     * Full listings from Gracenote's TV listings grid (the data behind most cable guides):
     * episode titles, descriptions, NEW/LIVE flags. A GitHub Action mirrors 24 hours of it every
     * couple of hours (Gracenote turns away requests from TVs), so the TV reads that copy first and
     * only tries Gracenote directly if the mirror is missing or stale.
     */
    private const val MIRROR = "https://github.com/vanstaverenalex/hearth-tv/releases/download/guide/gracenote.json"

    private fun gracenoteTexts(ctx: Context, g: GnLineup): List<String> {
        val dir = File(ctx.cacheDir, "guide").apply { mkdirs() }
        val mirrorCache = File(dir, "mirror.json")
        val now = System.currentTimeMillis()
        val mirror = fetchText(MIRROR, browser = false)?.takeIf { it.contains("\"channels\"") }
        if (mirror != null) try { mirrorCache.writeText(mirror) } catch (_: Exception) {}
        val m = mirror ?: if (mirrorCache.exists()) try { mirrorCache.readText() } catch (_: Exception) { null } else null
        if (m != null) {
            val updated = try { JSONObject(m).optLong("updated", 0L) * 1000 } catch (_: Exception) { 0L }
            if (now - updated < 14 * 3600_000L) return listOf(m)
        }
        // Mirror missing or old: ask Gracenote directly (works from some networks), keeping a last-good copy.
        val start = (now / 1000 / 1800) * 1800 - 1800
        val out = ArrayList<String>()
        for ((k, offset) in listOf(0L, 6 * 3600L).withIndex()) {
            val url = "https://tvlistings.gracenote.com/api/grid?lineupId=${g.lineupId}&timespan=6&headendId=${g.headendId}" +
                "&country=USA&timezone=&device=${g.device}&postalCode=${g.postalCode}&isOverride=true&time=${start + offset}" +
                "&pref=16%2C128&userId=-&aid=orbebb&languagecode=en-us"
            val cache = File(dir, "gracenote-$k.json")
            var json = fetchText(url, browser = true)?.takeIf { it.contains("\"channels\"") }
            if (json != null) try { cache.writeText(json) } catch (_: Exception) {}
            else if (cache.exists() && now - cache.lastModified() < 20 * 3600_000L) json = try { cache.readText() } catch (_: Exception) { null }
            if (json != null) out += json
        }
        if (out.isEmpty() && m != null) out += m   // an old mirror still beats nothing
        return out
    }

    private fun gracenote(ctx: Context, channels: List<Channel>, into: MutableMap<String, MutableList<Program>>): Int {
        val g = gnLineup ?: return 0
        val byCall = HashMap<String, String>()
        channels.forEach { c -> c.gn.forEach { byCall.putIfAbsent(it, c.id) } }
        if (byCall.isEmpty()) return 0
        var count = 0
        val got = HashSet<String>()
        for ((offset, json) in gracenoteTexts(ctx, g).withIndex()) {
            val arr = try { JSONObject(json).getJSONArray("channels") } catch (e: Exception) { null } ?: continue
            for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i)
                val id = byCall[c.optString("callSign")] ?: continue
                // Several call signs can map to one channel (HD/SD); keep the first one we see per window.
                val key = "$id@$offset"
                if (!got.add(key)) continue
                val evs = c.optJSONArray("events") ?: continue
                for (k in 0 until evs.length()) {
                    val e = evs.getJSONObject(k)
                    val p = e.optJSONObject("program") ?: continue
                    val st = parseIso(e.optString("startTime")) ?: continue
                    val en = parseIso(e.optString("endTime")) ?: continue
                    val flags = e.optJSONArray("flag")?.let { f -> (0 until f.length()).map { f.getString(it) } } ?: emptyList()
                    val season = p.optString("season", "").takeIf { it.isNotBlank() && it != "null" }
                    val ep = p.optString("episode", "").takeIf { it.isNotBlank() && it != "null" }
                    val epTitle = p.optString("episodeTitle", "").takeIf { it.isNotBlank() && it != "null" }
                    val detail = buildString {
                        if (season != null && ep != null) append("S$season E$ep")
                        if (epTitle != null) { if (isNotEmpty()) append("  ·  "); append(epTitle) }
                    }
                    into.getOrPut(id) { ArrayList() } += Program(
                        st, en, p.optString("title"), detail,
                        isNew = "New" in flags || "Premiere" in flags, isLive = "Live" in flags,
                        desc = p.optString("shortDesc", "").takeIf { it != "null" } ?: "", rich = true,
                    )
                    count++
                }
            }
        }
        return count
    }

    /** GET a URL as text with one retry; [browser] sends the same headers the Gracenote web guide does. */
    private fun fetchText(url: String, browser: Boolean): String? {
        repeat(2) { attempt ->
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000; readTimeout = 20000; instanceFollowRedirects = true
                    if (browser) {
                        setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
                        setRequestProperty("Accept", "application/json, text/plain, */*")
                        setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                        setRequestProperty("Referer", "https://tvlistings.gracenote.com/grid-affiliates.html?aid=orbebb")
                    } else setRequestProperty("User-Agent", "HearthTV/1.0")
                }
                val code = conn.responseCode
                if (code == 200) return conn.inputStream.bufferedReader().use { it.readText() }.also { conn.disconnect() }
                Log.w(TAG, "GET $code ${url.take(60)}"); conn.disconnect()
            } catch (e: Exception) { Log.w(TAG, "GET ${url.take(60)}: ${e.message}") }
            if (attempt == 0) Thread.sleep(1500)
        }
        return null
    }

    fun load(ctx: Context): Lineup {
        val channels = loadChannels(ctx)
        val rich = HashMap<String, MutableList<Program>>()
        val gnCount = try { gracenote(ctx, channels, rich) } catch (e: Exception) { 0 }
        val raw = HashMap<String, MutableList<Program>>()
        val cal = Calendar.getInstance()
        val today = cal.time
        cal.add(Calendar.DAY_OF_YEAR, 1)
        val tomorrow = cal.time
        cal.add(Calendar.DAY_OF_YEAR, -2)
        val yesterday = cal.time
        // Only reach for the backup sources when Gracenote didn't cover every channel.
        if (channels.any { rich[it.id].isNullOrEmpty() }) {
            for (d in listOf(yesterday, today, tomorrow)) tvmaze(d, channels, raw)
            // ESPN days are US-Eastern; ask for today and tomorrow (UTC games after 8pm ET land on "tomorrow").
            for (d in listOf(today, tomorrow)) espn(d, channels, raw)
        }

        val now = System.currentTimeMillis()
        val from = now - 60 * 60_000L
        val to = now + 10 * 3600_000L
        val out = HashMap<String, List<Program>>()
        for (ch in channels) {
            // Gracenote is complete for a channel when it has it; otherwise fall back to TVmaze + ESPN.
            val best = rich[ch.id]?.takeIf { it.isNotEmpty() }
            val list = (best ?: raw[ch.id] ?: ArrayList()).filter { it.end > from && it.start < to }
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
        Log.i(TAG, "guide: ${channels.size} channels, gracenote=$gnCount, fallback=${raw.values.sumOf { it.size }}")
        val covered = channels.count { !rich[it.id].isNullOrEmpty() }
        val source = if (covered > 0) "Gracenote · $covered of ${channels.size} channels" + (if (covered < channels.size) " · TVmaze · ESPN" else "")
            else "Basic listings (TVmaze · ESPN) · Gracenote unreachable"
        return Lineup(channels, out, now, source)
    }

    @Suppress("unused")
    private val utc = TimeZone.getTimeZone("UTC")
}
