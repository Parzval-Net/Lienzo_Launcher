package cl.deco.shelf.data.weather

import android.content.Context
import cl.deco.shelf.util.Http
import org.json.JSONObject
import java.net.URLEncoder

data class GeoPlace(val name: String, val admin: String?, val country: String?, val lat: Double, val lon: Double, val tz: String) {
    /** "Ciudad, Región, País" para mostrar en listas. */
    val label: String get() = listOfNotNull(name, admin?.takeIf { it.isNotBlank() && it != name }, country?.takeIf { it.isNotBlank() }).joinToString(", ")
}

/** Geocoding API de Open-Meteo (https://open-meteo.com/en/docs/geocoding-api). Bloqueante. */
object Geocoding {

    private class Hit(val at: Long, val places: List<GeoPlace>)
    private val memo = HashMap<String, Hit>()
    private const val TTL_MS = 24L * 3600_000

    /** Cache en memoria 24 h por consulta (los nombres de ciudades no cambian); Http deduplica las consultas en vuelo. */
    fun search(ctx: Context, query: String): List<GeoPlace> {
        val q = query.trim()
        val key = q.lowercase()
        synchronized(memo) { memo[key]?.let { if (System.currentTimeMillis() - it.at < TTL_MS) return it.places } }
        val url = "https://geocoding-api.open-meteo.com/v1/search?name=${URLEncoder.encode(q, "UTF-8")}&count=8&language=es&format=json"
        val r = Http.get(ctx, url, "Shelf-TV-Launcher/0.1", 8000, revalidate = false)
        val places = parse(r.body.orEmpty())
        synchronized(memo) { memo[key] = Hit(System.currentTimeMillis(), places) }
        return places
    }

    /** Sin resultados la API omite "results": lista vacía. Descarta entradas sin coordenadas. */
    fun parse(body: String): List<GeoPlace> {
        val arr = JSONObject(body).optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            if (!o.has("latitude") || !o.has("longitude") || !o.has("name")) return@mapNotNull null
            GeoPlace(
                name = o.getString("name"),
                admin = o.optString("admin1").takeIf { it.isNotBlank() },
                country = o.optString("country").takeIf { it.isNotBlank() },
                lat = o.getDouble("latitude"),
                lon = o.getDouble("longitude"),
                tz = o.optString("timezone").ifBlank { "auto" },
            )
        }
    }
}
