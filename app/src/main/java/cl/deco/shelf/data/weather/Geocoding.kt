package cl.deco.shelf.data.weather

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class GeoPlace(val name: String, val admin: String?, val country: String?, val lat: Double, val lon: Double, val tz: String) {
    /** "Ciudad, Región, País" para mostrar en listas. */
    val label: String get() = listOfNotNull(name, admin?.takeIf { it.isNotBlank() && it != name }, country?.takeIf { it.isNotBlank() }).joinToString(", ")
}

/** Geocoding API de Open-Meteo (https://open-meteo.com/en/docs/geocoding-api). Bloqueante. */
object Geocoding {

    fun search(query: String): List<GeoPlace> {
        val url = "https://geocoding-api.open-meteo.com/v1/search?name=${URLEncoder.encode(query.trim(), "UTF-8")}&count=8&language=es&format=json"
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000; c.readTimeout = 8000
            c.setRequestProperty("User-Agent", "Shelf-TV-Launcher/0.1")
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            return parse(c.inputStream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
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
