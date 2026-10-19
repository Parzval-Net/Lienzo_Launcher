package cl.deco.shelf.data.weather

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/** Tipo de icono por código WMO. Mapeo sobre la tabla de Open-Meteo (https://open-meteo.com/en/docs). */
enum class Kind { CLEAR, PARTLY, CLOUDY, FOG, DRIZZLE, RAIN, SNOW, STORM }

object WeatherCodes {
    /** Etiqueta en español para el código WMO. Códigos desconocidos caen en "Nublado". */
    fun label(code: Int): String = when (code) {
        0 -> "Despejado"
        1 -> "Mayormente despejado"
        2 -> "Parcialmente nublado"
        3 -> "Nublado"
        45, 48 -> "Neblina"
        51 -> "Llovizna leve"
        53 -> "Llovizna"
        55 -> "Llovizna intensa"
        56, 57 -> "Llovizna helada"
        61 -> "Lluvia leve"
        63 -> "Lluvia"
        65 -> "Lluvia intensa"
        66, 67 -> "Lluvia helada"
        71 -> "Nieve leve"
        73 -> "Nevada"
        75 -> "Nevada intensa"
        77 -> "Granizo fino"
        80 -> "Chubascos leves"
        81 -> "Chubascos"
        82 -> "Chubascos fuertes"
        85 -> "Nieve leve"
        86 -> "Nevadas intensas"
        95 -> "Tormenta"
        96, 99 -> "Tormenta con granizo"
        else -> "Nublado"
    }

    fun icon(code: Int): Kind = when (code) {
        0 -> Kind.CLEAR
        1, 2 -> Kind.PARTLY
        3 -> Kind.CLOUDY
        45, 48 -> Kind.FOG
        51, 53, 55, 56, 57 -> Kind.DRIZZLE
        61, 63, 65, 66, 67, 80, 81, 82 -> Kind.RAIN
        71, 73, 75, 77, 85, 86 -> Kind.SNOW
        95, 96, 99 -> Kind.STORM
        else -> Kind.CLOUDY
    }
}

/** Clima de Santiago vía Open-Meteo. Cache en filesDir con TTL 60 min; sin red devuelve la cache vencida. */
class OpenMeteoWeather(private val context: Context) : WeatherSource {

    override fun get(forceRefresh: Boolean): Weather? {
        val cached = readCache()
        val now = System.currentTimeMillis()
        if (!forceRefresh && cached != null && now - cached.fetchedAt < TTL_MS) return cached
        return try {
            parse(download(), now).also { writeCache(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Open-Meteo falló: ${e.message}")
            cached
        }
    }

    private fun download(): String {
        val conn = URL(URL_FORECAST).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", UA)
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(body: String, fetchedAt: Long): Weather {
        val root = JSONObject(body)
        val cur = root.getJSONObject("current")
        val daily = root.getJSONObject("daily")
        val times = daily.getJSONArray("time")
        val codes = daily.getJSONArray("weather_code")
        val tMax = daily.getJSONArray("temperature_2m_max")
        val tMin = daily.getJSONArray("temperature_2m_min")
        val precip = daily.getJSONArray("precipitation_probability_max")
        val days = (0 until times.length()).map { i ->
            DayForecast(
                date = LocalDate.parse(times.getString(i)),
                tMin = tMin.getDouble(i),
                tMax = tMax.getDouble(i),
                code = codes.getInt(i),
                precipProb = if (precip.isNull(i)) null else precip.getDouble(i).toInt(),
            )
        }
        return Weather(
            currentTemp = cur.getDouble("temperature_2m"),
            currentCode = cur.getInt("weather_code"),
            days = days,
            fetchedAt = fetchedAt,
        )
    }

    private fun cacheFile() = File(context.filesDir, "weather.json")

    private fun readCache(): Weather? = try {
        val o = JSONObject(cacheFile().readText())
        val arr: JSONArray = o.getJSONArray("days")
        val days = (0 until arr.length()).map { i ->
            val d = arr.getJSONObject(i)
            DayForecast(
                date = LocalDate.parse(d.getString("date")),
                tMin = d.getDouble("tMin"),
                tMax = d.getDouble("tMax"),
                code = d.getInt("code"),
                precipProb = if (d.isNull("precip")) null else d.getInt("precip"),
            )
        }
        Weather(o.getDouble("currentTemp"), o.getInt("currentCode"), days, o.getLong("fetchedAt"))
    } catch (e: Exception) {
        null
    }

    private fun writeCache(w: Weather) {
        try {
            val arr = JSONArray()
            w.days.forEach { d ->
                arr.put(
                    JSONObject()
                        .put("date", d.date.toString())
                        .put("tMin", d.tMin)
                        .put("tMax", d.tMax)
                        .put("code", d.code)
                        .put("precip", d.precipProb ?: JSONObject.NULL),
                )
            }
            val o = JSONObject()
                .put("currentTemp", w.currentTemp)
                .put("currentCode", w.currentCode)
                .put("fetchedAt", w.fetchedAt)
                .put("days", arr)
            val target = cacheFile()
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(target)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo escribir cache del clima: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "OpenMeteoWeather"
        const val UA = "Shelf-TV-Launcher/0.1"
        const val TIMEOUT_MS = 8_000
        const val TTL_MS = 60L * 60 * 1000
        const val URL_FORECAST =
            "https://api.open-meteo.com/v1/forecast?latitude=-33.4489&longitude=-70.6693" +
                "&timezone=America%2FSantiago&forecast_days=7" +
                "&current=temperature_2m,weather_code" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max"
    }
}
