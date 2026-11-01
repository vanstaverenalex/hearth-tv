package com.vanstaveren.hearthtv

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt

data class WeatherNow(
    val temp: Int,
    val high: Int,
    val low: Int,
    val code: Int,
    val isDay: Boolean,
    val sunriseMs: Long?,
    val sunsetMs: Long?,
)

/** Free weather from Open-Meteo, no account or key needed. */
object Weather {
    // Kalamazoo, MI. Change these two numbers to move the forecast.
    const val LAT = 42.29
    const val LON = -85.59

    fun fetch(): WeatherNow? = try {
        val url = URL(
            "https://api.open-meteo.com/v1/forecast?latitude=$LAT&longitude=$LON" +
                "&current=temperature_2m,weather_code,is_day" +
                "&daily=temperature_2m_max,temperature_2m_min,sunrise,sunset" +
                "&temperature_unit=fahrenheit&timezone=auto&forecast_days=1"
        )
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("User-Agent", "HearthTV/1.0")
        }
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        val json = JSONObject(body)
        val current = json.getJSONObject("current")
        val daily = json.getJSONObject("daily")
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US)
        fun parse(key: String): Long? = try {
            fmt.parse(daily.getJSONArray(key).getString(0))?.time
        } catch (e: Exception) { null }
        WeatherNow(
            temp = current.getDouble("temperature_2m").roundToInt(),
            high = daily.getJSONArray("temperature_2m_max").getDouble(0).roundToInt(),
            low = daily.getJSONArray("temperature_2m_min").getDouble(0).roundToInt(),
            code = current.getInt("weather_code"),
            isDay = current.optInt("is_day", 1) == 1,
            sunriseMs = parse("sunrise"),
            sunsetMs = parse("sunset"),
        )
    } catch (e: Exception) {
        null
    }

    /** WMO weather code → (icon, words). */
    fun describe(code: Int, isDay: Boolean): Pair<Int, String> = when (code) {
        0 -> (if (isDay) R.drawable.ic_w_sun else R.drawable.ic_w_moon) to "Clear"
        1 -> (if (isDay) R.drawable.ic_w_sun else R.drawable.ic_w_moon) to "Mostly clear"
        2 -> R.drawable.ic_w_cloud_sun to "Partly cloudy"
        3 -> R.drawable.ic_w_cloud to "Cloudy"
        45, 48 -> R.drawable.ic_w_fog to "Fog"
        51, 53, 55, 56, 57 -> R.drawable.ic_w_rain to "Drizzle"
        61, 63, 80, 81 -> R.drawable.ic_w_rain to "Rain"
        65, 82 -> R.drawable.ic_w_rain to "Heavy rain"
        66, 67 -> R.drawable.ic_w_rain to "Freezing rain"
        71, 73, 77, 85 -> R.drawable.ic_w_snow to "Snow"
        75, 86 -> R.drawable.ic_w_snow to "Heavy snow"
        95, 96, 99 -> R.drawable.ic_w_storm to "Thunderstorms"
        else -> R.drawable.ic_w_cloud to ""
    }
}
