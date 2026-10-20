package cl.deco.shelf.data.holidays

import android.content.Context
import android.util.Log
import cl.deco.shelf.util.Http
import cl.deco.shelf.settings.ShelfPrefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/**
 * Feriados públicos por país y año vía Nager.Date (/api/v3/PublicHolidays/{year}/{CC}); CL por defecto.
 * País y región se leen de ajustes en cada llamada. Orden: cache fresca (30 días, por país y año, con todos los
 * feriados incluidos los regionales) -> Nager -> cache vencida -> cálculo offline (solo CL).
 * Región: filtra por el campo counties de Nager (p. ej. CL-AP). Sin región solo se devuelven los nacionales.
 * Bloqueante: llamar fuera del main thread.
 */
class HolidaysRepository(private val context: Context, private val prefs: ShelfPrefs = ShelfPrefs(context)) : HolidaySource {

    override fun forYear(year: Int): List<Holiday> {
        val cc = prefs.holidaysCountry.uppercase()
        return filterRegion(all(year, cc), prefs.holidaysRegion)
    }

    private fun all(year: Int, cc: String): List<Holiday> {
        val now = System.currentTimeMillis()
        val cached = readCache(year, cc)
        if (cached != null && now - cached.fetchedAt < TTL_MS) return cached.holidays

        val online = try {
            fetchNager(year, cc)
        } catch (e: Exception) {
            Log.w(TAG, "Nager falló para $cc $year: ${e.message}")
            null
        }
        if (online === NOT_MODIFIED && cached != null) {
            writeCache(year, cc, Cache(now, cached.holidays)) // 304: mismos datos, TTL renovado
            return cached.holidays
        }
        if (online != null && online !== NOT_MODIFIED) {
            writeCache(year, cc, Cache(now, online))
            return online
        }
        if (cached != null) return cached.holidays
        return if (cc == "CL") HolidayRules.forYear(year) else emptyList()
    }

    private data class Cache(val fetchedAt: Long, val holidays: List<Holiday>)

    private fun fetchNager(year: Int, cc: String): List<Holiday>? {
        val r = Http.get(context, "https://date.nager.at/api/v3/PublicHolidays/$year/$cc", UA, TIMEOUT_MS, revalidate = readCache(year, cc) != null)
        if (r.notModified) return NOT_MODIFIED // el llamador conserva su cache y solo renueva la fecha
        val arr = JSONArray(r.body ?: return null)
        val out = parseNager(arr, year, cc)
        // Validación mínima solo para Chile (>= 10 nacionales); para otros países basta con tener datos
        if (cc == "CL" && out.count { it.counties.isEmpty() } < 10) return null
        if (out.isEmpty()) return null
        return out.sortedBy { it.date }
    }

    private fun cacheFile(year: Int, cc: String) = File(File(context.filesDir, "holidays").apply { mkdirs() }, "$cc-$year.json")

    private fun readCache(year: Int, cc: String): Cache? = try {
        val root = JSONObject(cacheFile(year, cc).readText())
        val arr = root.getJSONArray("holidays")
        val list = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val c = o.optJSONArray("counties")
            Holiday(
                LocalDate.parse(o.getString("date")), o.getString("name"), o.getBoolean("irr"),
                if (c == null) emptyList() else (0 until c.length()).map { c.getString(it) },
            )
        }
        Cache(root.getLong("fetchedAt"), list)
    } catch (e: Exception) {
        null
    }

    private fun writeCache(year: Int, cc: String, cache: Cache) {
        try {
            val arr = JSONArray()
            cache.holidays.forEach { h ->
                arr.put(JSONObject().put("date", h.date.toString()).put("name", h.name).put("irr", h.irrenunciable)
                    .put("counties", JSONArray(h.counties)))
            }
            val root = JSONObject().put("fetchedAt", cache.fetchedAt).put("holidays", arr)
            val target = cacheFile(year, cc)
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeText(root.toString())
            tmp.renameTo(target)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo escribir cache de feriados: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "HolidaysRepository"
        private val NOT_MODIFIED: List<Holiday> = ArrayList()
        private const val UA = "Shelf-TV-Launcher/0.1"
        private const val TIMEOUT_MS = 8_000
        private const val TTL_MS = 30L * 24 * 60 * 60 * 1000

        /** Nacionales siempre; regionales solo si [region] está en sus counties. */
        fun filterRegion(all: List<Holiday>, region: String): List<Holiday> =
            all.filter { it.counties.isEmpty() || (region.isNotEmpty() && region in it.counties) }

        /** Parseo de la respuesta de Nager.Date. counties null = feriado nacional. */
        fun parseNager(arr: JSONArray, year: Int, cc: String): List<Holiday> {
            val out = ArrayList<Holiday>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val date = LocalDate.parse(o.getString("date"))
                if (date.year != year) continue
                val c = o.optJSONArray("counties")
                val counties = if (c == null) emptyList() else (0 until c.length()).map { c.getString(it) }
                val name = o.optString("localName").ifBlank { o.getString("name") }
                out += Holiday(date, name, cc == "CL" && HolidayRules.isIrrenunciable(date), counties)
            }
            return out
        }
    }
}
