package cl.deco.shelf.data.holidays

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/**
 * Feriados nacionales de Chile por año.
 * Orden: cache fresca (30 días) -> Nager.Date (nombres en español, campo localName) -> cache vencida -> cálculo offline.
 * Respaldo del Gobierno Digital (apis.digital.gob.cl) no implementado: el host no resolvió DNS desde el Mac al 2026-10-19.
 * Bloqueante: llamar fuera del main thread.
 */
class ChileHolidays(private val context: Context) : HolidaySource {

    override fun forYear(year: Int): List<Holiday> {
        val now = System.currentTimeMillis()
        val cached = readCache(year)
        if (cached != null && now - cached.fetchedAt < TTL_MS) return cached.holidays

        val online = try {
            fetchNager(year)
        } catch (e: Exception) {
            Log.w(TAG, "Nager falló para $year: ${e.message}")
            null
        }
        if (online != null) {
            writeCache(year, Cache(now, online))
            return online
        }
        // Red caída: cache vencida con nombres oficiales antes que el cálculo offline
        if (cached != null) return cached.holidays
        return HolidayRules.forYear(year)
    }

    private data class Cache(val fetchedAt: Long, val holidays: List<Holiday>)

    private fun fetchNager(year: Int): List<Holiday>? {
        val body = get("https://date.nager.at/api/v3/PublicHolidays/$year/CL")
        val arr = JSONArray(body)
        val out = ArrayList<Holiday>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            // Solo feriados nacionales: counties null (excluye Arica, Magallanes, etc.)
            if (o.optJSONArray("counties") != null) continue
            val date = LocalDate.parse(o.getString("date"))
            if (date.year != year) continue
            val name = o.optString("localName").ifBlank { o.getString("name") }
            out += Holiday(date, name, HolidayRules.isIrrenunciable(date))
        }
        // Validación mínima: un año chileno tiene al menos 10 feriados nacionales
        if (out.size < 10) return null
        return out.sortedBy { it.date }
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", UA)
            if (conn.responseCode != 200) throw java.io.IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun cacheFile(year: Int) = File(File(context.filesDir, "holidays").apply { mkdirs() }, "$year.json")

    private fun readCache(year: Int): Cache? = try {
        val root = JSONObject(cacheFile(year).readText())
        val arr = root.getJSONArray("holidays")
        val list = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Holiday(LocalDate.parse(o.getString("date")), o.getString("name"), o.getBoolean("irr"))
        }
        Cache(root.getLong("fetchedAt"), list)
    } catch (e: Exception) {
        null
    }

    private fun writeCache(year: Int, cache: Cache) {
        try {
            val arr = JSONArray()
            cache.holidays.forEach { h ->
                arr.put(JSONObject().put("date", h.date.toString()).put("name", h.name).put("irr", h.irrenunciable))
            }
            val root = JSONObject().put("fetchedAt", cache.fetchedAt).put("holidays", arr)
            val target = cacheFile(year)
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeText(root.toString())
            tmp.renameTo(target)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo escribir cache de feriados: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "ChileHolidays"
        const val UA = "Shelf-TV-Launcher/0.1"
        const val TIMEOUT_MS = 8_000
        const val TTL_MS = 30L * 24 * 60 * 60 * 1000
    }
}
