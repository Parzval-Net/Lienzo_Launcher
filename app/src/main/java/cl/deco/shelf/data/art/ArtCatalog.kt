package cl.deco.shelf.data.art

import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Obra del catálogo embebido (assets/art_index.json). [crop] = [x, y, w, h] normalizado 0..1 sobre la miniatura de [url]. */
data class CatalogItem(
    val id: String,
    val title: String,
    val artist: String,
    val year: String,
    val museum: String,
    val license: String,
    val mode: String,
    val home: Boolean,
    val mat: Boolean,
    val url: String,
    val crop: List<Double>,
    val sourceUrl: String,
) {
    val file: String get() = "$id.jpg"
}

data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** Lógica pura (testeable en JVM) del catálogo en línea. */
object ArtCatalog {
    const val OUT_W = 1920
    const val OUT_H = 1080
    const val MAT_W_FRAC = 0.78
    const val MAT_H_FRAC = 0.80
    private const val COMMONS_WIKI = "https://commons.wikimedia.org/wiki/"

    fun parse(json: String): List<CatalogItem> {
        val arr = JSONObject(json).getJSONArray("items")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val c = o.getJSONArray("crop")
            require(c.length() == 4) { "crop inválido en ${o.optString("id")}" }
            val mat = o.optBoolean("mat", false)
            CatalogItem(
                id = o.getString("id"),
                title = o.getString("title"),
                artist = o.optString("artist"),
                year = o.optString("year"),
                museum = o.optString("museum"),
                license = o.optString("license"),
                mode = o.optString("mode", if (mat) "mat" else "crop"),
                home = o.optBoolean("home", true),
                mat = mat,
                url = o.getString("url"),
                crop = (0 until 4).map { c.getDouble(it) },
                sourceUrl = o.optString("src").takeIf { it.isNotBlank() }?.let { COMMONS_WIKI + it }.orEmpty(),
            )
        }
    }

    /** Rect en píxeles de una imagen [imgW]x[imgH] a partir del crop normalizado, acotado a los límites. */
    fun cropRect(crop: List<Double>, imgW: Int, imgH: Int): PixelRect {
        val l = (crop[0] * imgW).roundToInt().coerceIn(0, imgW - 1)
        val t = (crop[1] * imgH).roundToInt().coerceIn(0, imgH - 1)
        val r = ((crop[0] + crop[2]) * imgW).roundToInt().coerceIn(l + 1, imgW)
        val b = ((crop[1] + crop[3]) * imgH).roundToInt().coerceIn(t + 1, imgH)
        return PixelRect(l, t, r, b)
    }

    /** Mayor potencia de 2 tal que el rect decodificado siga siendo >= [targetW] de ancho. */
    fun sampleSize(rectW: Int, targetW: Int): Int {
        var s = 1
        while (rectW / (s * 2) >= targetW) s *= 2
        return s
    }

    /** Tamaño de la obra dentro del passe-partout (misma regla que process.py: cabe en 78% x 80% del lienzo). */
    fun matArtSize(rectW: Int, rectH: Int): Pair<Int, Int> {
        val mw = (OUT_W * MAT_W_FRAC).toInt()
        val mh = (OUT_H * MAT_H_FRAC).toInt()
        val s = min(mw.toDouble() / rectW, mh.toDouble() / rectH)
        return max(1, (rectW * s).roundToInt()) to max(1, (rectH * s).roundToInt())
    }
}
