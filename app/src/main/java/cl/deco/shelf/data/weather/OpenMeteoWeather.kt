package cl.deco.shelf.data.weather

import android.content.Context
import android.util.Log
import cl.deco.shelf.settings.ShelfPrefs
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

/**
 * Clima vía Open-Meteo para la ciudad guardada en ajustes (Santiago por defecto). Cache en filesDir con TTL 30 min,
 * ligada a la ubicación: si cambia la ciudad la cache no sirve. Sin red devuelve la cache vencida de la misma ciudad.
 */
class OpenMeteoWeather(private val context: Context) : WeatherSource {

    private val prefs = ShelfPrefs(context)

    override fun get(forceRefresh: Boolean): Weather? {
        val locKey = prefs.cityKey()
        val cached = readCache(locKey)
        val now = System.currentTimeMillis()
        if (!forceRefresh && cached != null && now - cached.fetchedAt < TTL_MS) return cached
        return try {
            parse(download(), now).also { writeCache(it, locKey) }
        } catch (e: Exception) {
            Log.w(TAG, "Open-Meteo falló: ${e.message}")
            cached
        }
    }

    private fun download(): String {
        val conn = URL(forecastUrl()).openConnection() as HttpURLConnection
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

    private fun forecastUrl(): String =
        "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&timezone=%s".format(
            java.util.Locale.US, prefs.cityLat, prefs.cityLon, java.net.URLEncoder.encode(prefs.cityTz, "UTF-8"),
        ) + URL_PARAMS

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
            isDay = cur.optInt("is_day", 1) == 1,
            rainSoonProb = rainSoon(root.optJSONObject("hourly"), cur.getString("time")),
        )
    }

    // Máximo de precipitation_probability desde la hora actual y las 2 siguientes.
    private fun rainSoon(hourly: JSONObject?, nowIso: String): Int? {
        hourly ?: return null
        val times = hourly.getJSONArray("time")
        val probs = hourly.getJSONArray("precipitation_probability")
        val hour = nowIso.take(13)
        val start = (0 until times.length()).firstOrNull { times.getString(it).startsWith(hour) } ?: return null
        return (start until minOf(start + 3, times.length()))
            .filterNot { probs.isNull(it) }
            .maxOfOrNull { probs.getInt(it) }
    }

    private fun cacheFile() = File(context.filesDir, "weather.json")

    private fun readCache(locKey: String): Weather? = try {
        val o = JSONObject(cacheFile().readText())
        if (o.optString("loc") != locKey) throw IOException("otra ubicación")
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
        Weather(
            o.getDouble("currentTemp"), o.getInt("currentCode"), days, o.getLong("fetchedAt"),
            isDay = o.optBoolean("isDay", true),
            rainSoonProb = if (o.isNull("rainSoon")) null else o.optInt("rainSoon"),
        )
    } catch (e: Exception) {
        null
    }

    private fun writeCache(w: Weather, locKey: String) {
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
                .put("loc", locKey)
                .put("fetchedAt", w.fetchedAt)
                .put("isDay", w.isDay)
                .put("rainSoon", w.rainSoonProb ?: JSONObject.NULL)
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
        const val TIMEOUT_MS = 15_000
        const val TTL_MS = 30L * 60 * 1000
        const val URL_PARAMS = "&forecast_days=7" +
            "&current=temperature_2m,weather_code,is_day" +
            "&hourly=precipitation_probability&forecast_hours=4" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max"
    }
}
