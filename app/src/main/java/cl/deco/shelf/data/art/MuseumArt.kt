package cl.deco.shelf.data.art

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.max
import kotlin.random.Random

/**
 * Obras de dominio público (CC0) para el modo arte.
 * Colección curada local: si `getExternalFilesDir("art")` trae index.json con obras (se carga por adb push,
 * ya recortadas a 16:9), se usa solo esa y no se descarga nada. Si está vacía, modo online:
 * Primaria: Art Institute of Chicago (api.artic.edu, IIIF). Respaldo: The Met Collection API (v1.1 search).
 * Bloqueante: llamar desde executor.
 */
class MuseumArt(private val context: Context) : ArtSource {

    private val artDir by lazy { File(context.cacheDir, "art").apply { mkdirs() } }
    private val indexFile by lazy { File(artDir, "index.json") }
    private val prefs by lazy { context.getSharedPreferences("museum_art", Context.MODE_PRIVATE) }
    private val indexLock = Any()
    private val settings by lazy { cl.deco.shelf.settings.ShelfPrefs(context) }

    private data class Entry(
        val id: String,
        val title: String,
        val artist: String,
        val year: String?,
        val museum: String,
        val fileName: String,
        val bytes: Long,
        val addedAt: Long,
    )

    private data class Candidate(
        val id: String,
        val title: String,
        val artist: String,
        val year: String?,
        val museum: String,
        val imageUrl: String,
        val maxBytes: Int,
    )

    private enum class Source { AIC, MET }

    /** Obra de la colección curada local (index.json generado en el Mac). [home] false = excluida del fondo. */
    private data class LocalEntry(val art: Artwork, val home: Boolean)

    private val localDir: File? by lazy { context.getExternalFilesDir("art") }
    private var localCache: List<LocalEntry> = emptyList()
    private var localStamp = -1L

    /** Lee index.json local (cacheado según lastModified). Lista vacía si no hay colección. */
    private fun loadLocal(): List<LocalEntry> = synchronized(indexLock) {
        val dir = localDir ?: return emptyList()
        val f = File(dir, "index.json")
        val stamp = if (f.exists()) f.lastModified() else 0L
        if (stamp == localStamp) return localCache
        localStamp = stamp
        localCache = try {
            if (stamp == 0L) emptyList() else {
                val arr = JSONArray(f.readText())
                val list = (0 until arr.length()).mapNotNull { i ->
                    val o = arr.getJSONObject(i)
                    val img = File(dir, o.getString("file"))
                    if (!img.exists()) return@mapNotNull null
                    LocalEntry(
                        Artwork(
                            id = "local-" + o.getString("id"),
                            title = o.getString("title"),
                            artist = o.optString("artist"),
                            year = o.optString("year").takeIf { it.isNotBlank() },
                            museum = o.optString("museum"),
                            file = img,
                        ),
                        home = o.optBoolean("home", true),
                    )
                }
                list.shuffled(Random(LOCAL_SEED)) // orden fijo pero sin agrupar por artista
            }
        } catch (e: Exception) {
            Log.w(TAG, "index.json local inválido: ${e.message}")
            emptyList()
        }
        localCache
    }

    private fun nextLocal(forHome: Boolean): Artwork? {
        val list = loadLocal().filter { !forHome || it.home }
        if (list.isEmpty()) return null
        val key = if (forHome) KEY_LAST_LOCAL_HOME else KEY_LAST_LOCAL_ALL
        val idx = (prefs.getInt(key, -1) + 1).mod(list.size)
        prefs.edit().putInt(key, idx).apply()
        return list[idx].art
    }

    override fun next(forHome: Boolean): Artwork? = synchronized(indexLock) {
        if (loadLocal().isNotEmpty() && !(forHome && settings.bgOnline)) return nextLocal(forHome)
        val entries = loadIndex()
        if (entries.isEmpty()) return null
        val last = prefs.getInt(KEY_LAST, -1)
        val idx = (last + 1).mod(entries.size)
        prefs.edit().putInt(KEY_LAST, idx).apply()
        val e = entries[idx]
        Artwork(e.id, e.title, e.artist, e.year, e.museum, File(artDir, e.fileName))
    }

    override fun prefetch(max: Int) {
        if (max <= 0) return
        if (loadLocal().isNotEmpty() && !settings.bgOnline) return // colección curada local: no se descarga nada
        val have = synchronized(indexLock) { loadIndex().map { it.id }.toMutableSet() }
        var added = 0
        val artists = CURATED.shuffled(Random(System.nanoTime()))
        for (source in Source.values()) {
            var attempts = 0
            val maxAttempts = max * 4 + 8
            for (artist in artists) {
                if (added >= max || attempts >= maxAttempts) break
                val candidates = try {
                    when (source) {
                        Source.AIC -> aicCandidates(artist)
                        Source.MET -> metCandidates(artist)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Búsqueda $source falló para ${artist.name}: ${e.message}")
                    emptyList()
                }
                for (c in candidates.shuffled()) {
                    if (added >= max || attempts >= maxAttempts) break
                    if (c.id in have) continue
                    attempts++
                    if (store(c)) {
                        added++
                        have += c.id
                    }
                }
            }
            if (added >= max) break
        }
        Log.i(TAG, "prefetch: $added obras nuevas (pedidas $max)")
    }

    // ---- Fuentes ----

    private fun aicCandidates(artist: Artist): List<Candidate> {
        val url = "https://api.artic.edu/api/v1/artworks/search" +
            "?q=${enc(artist.name)}&query[term][is_public_domain]=true" +
            "&fields=id,title,artist_title,date_display,image_id,thumbnail,is_public_domain&limit=40"
        val data = JSONObject(getText(url)).optJSONArray("data") ?: return emptyList()
        val out = ArrayList<Candidate>()
        for (i in 0 until data.length()) {
            val o = data.getJSONObject(i)
            val imageId = o.optString("image_id").takeIf { it.isNotBlank() && it != "null" } ?: continue
            if (!o.optBoolean("is_public_domain", false)) continue
            if (!o.optString("artist_title").contains(artist.surname, ignoreCase = true)) continue
            val thumb = o.optJSONObject("thumbnail") ?: continue
            val w = thumb.optInt("width")
            val h = thumb.optInt("height")
            if (h <= 0 || w.toFloat() / h < MIN_RATIO) continue
            out += Candidate(
                id = "aic-${o.getInt("id")}",
                title = o.optString("title"),
                artist = o.optString("artist_title").ifBlank { artist.name },
                year = o.optString("date_display").takeIf { it.isNotBlank() },
                museum = "Art Institute of Chicago",
                imageUrl = "https://www.artic.edu/iiif/2/$imageId/full/1686,/0/default.jpg",
                maxBytes = MAX_DOWNLOAD_AIC,
            )
        }
        return out
    }

    private fun metCandidates(artist: Artist): List<Candidate> {
        val url = "https://collectionapi.metmuseum.org/public/collection/v1.1/search" +
            "?isPublicDomain=true&hasImages=true&q=${enc(artist.name)}"
        val ids = JSONObject(getText(url)).optJSONArray("objectIDs") ?: return emptyList()
        val out = ArrayList<Candidate>()
        for (i in 0 until minOf(ids.length(), MET_OBJECTS_PER_ARTIST)) {
            val id = ids.getInt(i)
            try {
                val o = JSONObject(getText("https://collectionapi.metmuseum.org/public/collection/v1/objects/$id"))
                val img = o.optString("primaryImage")
                if (!o.optBoolean("isPublicDomain") || img.isBlank()) continue
                if (!o.optString("artistDisplayName").contains(artist.surname, ignoreCase = true)) continue
                out += Candidate(
                    id = "met-$id",
                    title = o.optString("title"),
                    artist = o.optString("artistDisplayName").ifBlank { artist.name },
                    year = o.optString("objectDate").takeIf { it.isNotBlank() },
                    museum = "The Met",
                    imageUrl = img,
                    maxBytes = MAX_DOWNLOAD_MET,
                )
            } catch (e: Exception) {
                Log.w(TAG, "Met objeto $id: ${e.message}")
            }
        }
        return out
    }

    // ---- Descarga, transcodificación y cache ----

    /** Descarga la imagen, la reduce a [MAX_SIDE] y la guarda como JPEG. Devuelve false si no sirve (ratio, error). */
    private fun store(c: Candidate): Boolean {
        val bytes = try {
            download(c.imageUrl, c.maxBytes)
        } catch (e: Exception) {
            Log.w(TAG, "Descarga ${c.id} falló: ${e.message}")
            return false
        }
        val fileName = "${c.id}.jpg"
        val dest = File(artDir, fileName)
        if (!transcode(bytes, dest)) {
            dest.delete()
            return false
        }
        synchronized(indexLock) {
            val entries = loadIndex().filter { it.id != c.id }.toMutableList()
            entries += Entry(
                id = c.id,
                title = c.title,
                artist = c.artist,
                year = c.year,
                museum = c.museum,
                fileName = fileName,
                bytes = dest.length(),
                addedAt = System.currentTimeMillis(),
            )
            enforceBudget(entries)
            saveIndex(entries)
        }
        return true
    }

    /** Borra las obras más antiguas hasta quedar bajo [CACHE_LIMIT_BYTES]. Modifica [entries]. */
    private fun enforceBudget(entries: MutableList<Entry>) {
        var total = entries.sumOf { it.bytes }
        if (total <= CACHE_LIMIT_BYTES) return
        entries.sortBy { it.addedAt }
        while (total > CACHE_LIMIT_BYTES && entries.isNotEmpty()) {
            val old = entries.removeAt(0)
            File(artDir, old.fileName).delete()
            total -= old.bytes
        }
    }

    private fun transcode(bytes: ByteArray, dest: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0 || w.toFloat() / h < MIN_RATIO) return false
        var sample = 1
        while (max(w, h) / (sample * 2) >= MAX_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp: Bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return false
        return try {
            dest.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        } finally {
            bmp.recycle()
        }
    }

    private fun download(url: String, maxBytes: Int): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = IMG_TIMEOUT_MS
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Accept", "image/jpeg,image/*;q=0.8")
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            val out = ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > maxBytes) throw IOException("imagen > $maxBytes bytes")
                }
            }
            return out.toByteArray()
        } finally {
            conn.disconnect()
        }
    }

    private fun getText(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
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

    private fun loadIndex(): List<Entry> = try {
        val arr = JSONArray(indexFile.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Entry(
                id = o.getString("id"),
                title = o.getString("title"),
                artist = o.getString("artist"),
                year = o.optString("year").takeIf { it.isNotBlank() },
                museum = o.getString("museum"),
                fileName = o.getString("file"),
                bytes = o.getLong("bytes"),
                addedAt = o.getLong("addedAt"),
            )
        }.filter { File(artDir, it.fileName).exists() }.sortedBy { it.addedAt }
    } catch (e: Exception) {
        emptyList()
    }

    private fun saveIndex(entries: List<Entry>) {
        try {
            val arr = JSONArray()
            entries.forEach { e ->
                arr.put(
                    JSONObject()
                        .put("id", e.id)
                        .put("title", e.title)
                        .put("artist", e.artist)
                        .put("year", e.year ?: "")
                        .put("museum", e.museum)
                        .put("file", e.fileName)
                        .put("bytes", e.bytes)
                        .put("addedAt", e.addedAt),
                )
            }
            val tmp = File(artDir, "index.json.tmp")
            tmp.writeText(arr.toString())
            tmp.renameTo(indexFile)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo guardar index.json: ${e.message}")
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private data class Artist(val name: String, val surname: String)

    private companion object {
        const val TAG = "MuseumArt"
        const val UA = "Shelf-TV-Launcher/0.1"
        const val KEY_LAST = "last_index"
        const val KEY_LAST_LOCAL_HOME = "last_local_home"
        const val KEY_LAST_LOCAL_ALL = "last_local_all"
        const val LOCAL_SEED = 585
        const val TIMEOUT_MS = 8_000
        const val IMG_TIMEOUT_MS = 15_000
        const val MIN_RATIO = 1.2f
        const val MAX_SIDE = 1920
        const val JPEG_QUALITY = 88
        const val CACHE_LIMIT_BYTES = 40L * 1024 * 1024
        const val MAX_DOWNLOAD_AIC = 6 * 1024 * 1024
        const val MAX_DOWNLOAD_MET = 15 * 1024 * 1024
        const val MET_OBJECTS_PER_ARTIST = 6

        /** Pintores curados (impresionistas, postimpresionistas y paisajistas clásicos de dominio público). */
        private val CURATED = listOf(
            Artist("Claude Monet", "Monet"),
            Artist("Pierre-Auguste Renoir", "Renoir"),
            Artist("Edgar Degas", "Degas"),
            Artist("Camille Pissarro", "Pissarro"),
            Artist("Alfred Sisley", "Sisley"),
            Artist("Berthe Morisot", "Morisot"),
            Artist("Paul Cézanne", "Cézanne"),
            Artist("Vincent van Gogh", "Gogh"),
            Artist("Gustave Caillebotte", "Caillebotte"),
            Artist("Georges Seurat", "Seurat"),
            Artist("J. M. W. Turner", "Turner"),
            Artist("Katsushika Hokusai", "Hokusai"),
            Artist("Utagawa Hiroshige", "Hiroshige"),
        )
    }
}
