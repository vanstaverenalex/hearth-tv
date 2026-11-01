package com.vanstaveren.hearthtv

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

data class WeatherNow(
    val temp: Int,
    val high: Int,
    val low: Int,
    val code: Int,
    val isDay: Boolean,
)

/** Free weather from Open-Meteo — no account or API key needed. */
object Weather {
    // Kalamazoo, MI. Change these two numbers to move the forecast.
    const val LAT = 42.29
    const val LON = -85.59

    fun fetch(): WeatherNow? = try {
        val url = URL(
            "https://api.open-meteo.com/v1/forecast?latitude=$LAT&longitude=$LON" +
                "&current=temperature_2m,weather_code,is_day" +
                "&daily=temperature_2m_max,temperature_2m_min" +
                "&temperature_unit=fahrenheit&timezone=auto&forecast_days=1"
        )
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
        }
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()
        val json = JSONObject(body)
        val current = json.getJSONObject("current")
        val daily = json.getJSONObject("daily")
        WeatherNow(
            temp = current.getDouble("temperature_2m").roundToInt(),
            high = daily.getJSONArray("temperature_2m_max").getDouble(0).roundToInt(),
            low = daily.getJSONArray("temperature_2m_min").getDouble(0).roundToInt(),
            code = current.getInt("weather_code"),
            isDay = current.optInt("is_day", 1) == 1,
        )
    } catch (e: Exception) {
        null
    }

    /** WMO weather code → (emoji, words). */
    fun describe(code: Int, isDay: Boolean): Pair<String, String> = when (code) {
        0 -> (if (isDay) "☀️" else "🌙") to "Clear"
        1 -> (if (isDay) "🌤️" else "🌙") to "Mostly clear"
        2 -> "⛅" to "Partly cloudy"
        3 -> "☁️" to "Cloudy"
        45, 48 -> "🌫️" to "Fog"
        51, 53, 55, 56, 57 -> "🌦️" to "Drizzle"
        61, 63, 80, 81 -> "🌧️" to "Rain"
        65, 82 -> "🌧️" to "Heavy rain"
        66, 67 -> "🌧️" to "Freezing rain"
        71, 73, 85 -> "🌨️" to "Snow"
        75, 86 -> "❄️" to "Heavy snow"
        77 -> "🌨️" to "Snow grains"
        95, 96, 99 -> "⛈️" to "Thunderstorms"
        else -> "🌡️" to ""
    }
}
