package cl.deco.shelf.data.art

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Obras de dominio público (Wikimedia Commons) para el modo arte.
 *
 * Colección curada de 60 obras en `getExternalFilesDir("art")` (index.json + JPEG 1920x1080), cargada por adb push
 * o descargada por la propia app. Si la carpeta está vacía o incompleta, [startFullDownload] baja desde Commons,
 * según assets/art_index.json, las obras faltantes: una a la vez, con reintentos y backoff, solo con red sin
 * medición de datos. Cada obra se recorta con BitmapRegionDecoder, se escala a 1920x1080 (con passe-partout
 * si corresponde) y se guarda como JPEG q82. Tras la primera descarga todo funciona offline.
 * Bloqueante: llamar desde executor (la descarga corre en su propio hilo).
 */
class MuseumArt(private val context: Context) : ArtSource {

    private val app = context.applicationContext
    private val prefs by lazy { app.getSharedPreferences("museum_art", Context.MODE_PRIVATE) }
    private val indexLock = Any()
    private val localDir: File? by lazy { app.getExternalFilesDir("art") }
    private var localCache: List<LocalEntry> = emptyList()
    private var localStamp = -1L

    /** Obra de la colección curada local. [home] false = excluida del fondo. */
    private data class LocalEntry(val art: Artwork, val home: Boolean)

    private fun indexFile(dir: File) = File(dir, "index.json")

    /** Lee index.json local (cacheado según lastModified). Lista vacía si no hay colección. */
    private fun loadLocal(): List<LocalEntry> = synchronized(indexLock) {
        val dir = localDir ?: return emptyList()
        val f = indexFile(dir)
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

    override fun next(forHome: Boolean): Artwork? = synchronized(indexLock) {
        val list = loadLocal().filter { !forHome || it.home }
        if (list.isEmpty()) return null
        val key = if (forHome) KEY_LAST_LOCAL_HOME else KEY_LAST_LOCAL_ALL
        val idx = (prefs.getInt(key, -1) + 1).mod(list.size)
        prefs.edit().putInt(key, idx).apply()
        list[idx].art
    }

    /** La colección se completa sola: si falta algo, sigue la descarga. */
    override fun prefetch(max: Int) {
        if (max > 0) startFullDownload()
    }

    // ---- Progreso ----

    override fun downloadProgress(): Pair<Int, Int> {
        val cat = catalog()
        if (cat.isEmpty()) return 0 to 0
        val have = presentIds()
        return cat.count { it.id in have } to cat.size
    }

    private fun catalog(): List<CatalogItem> = synchronized(Companion) {
        catalogCache ?: try {
            app.assets.open(ASSET_INDEX).bufferedReader().use { ArtCatalog.parse(it.readText()) }
        } catch (e: Exception) {
            Log.w(TAG, "art_index.json inválido: ${e.message}")
            emptyList()
        }.also { catalogCache = it }
    }

    /** ids (sin prefijo) presentes en index.json con su JPEG en disco. */
    private fun presentIds(): Set<String> = synchronized(indexLock) {
        val dir = localDir ?: return emptySet()
        try {
            val f = indexFile(dir)
            if (!f.exists()) return emptySet()
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { arr.getJSONObject(it) }
                .filter { File(dir, it.getString("file")).length() > 0 }
                .map { it.getString("id") }.toSet()
        } catch (e: Exception) {
            emptySet()
        }
    }

    // ---- Descarga ----

    override fun startFullDownload() {
        val dir = localDir ?: return
        val cat = catalog()
        if (cat.isEmpty()) return
        val flag = File(dir, ONLINE_FLAG)
        val present = presentIds()
        // Carpeta con una colección ajena (adb push) y sin marca de descarga propia: no se toca.
        if (present.isNotEmpty() && !flag.exists() && cat.none { it.id !in present }) return
        if (cat.all { it.id in present }) return
        if (!running.compareAndSet(false, true)) return
        Thread({
            try {
                runDownload(dir, cat)
            } catch (t: Throwable) {
                Log.w(TAG, "descarga abortada: $t")
            } finally {
                running.set(false)
            }
        }, "lienzo-art-download").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    private fun runDownload(dir: File, cat: List<CatalogItem>) {
        File(dir, ONLINE_FLAG).apply { if (!exists()) writeText("1") }
        val t0 = System.currentTimeMillis()
        var done = 0
        for (item in cat) {
            if (item.id in presentIds()) continue
            var ok = false
            var attempt = 0       // fallas "normales"
            var throttled = 0     // 429/503: Commons limita por IP, se espera más sin descartar la obra
            var waitMs = 0L
            while (!ok && attempt < MAX_ATTEMPTS && throttled < MAX_THROTTLED) {
                if (!networkAllowed()) {
                    Log.i(TAG, "sin red (o con medición): descarga en pausa; se reanuda en el próximo arranque")
                    return
                }
                if (waitMs > 0) Thread.sleep(waitMs)
                waitForAllowed()
                ok = try {
                    process(item, dir).also {
                        if (it) prefs.edit().putInt(KEY_BACKOFF_LEVEL, 0).apply()
                        else { attempt++; waitMs = BACKOFF_MS shl (attempt - 1) }
                    }
                } catch (e: HttpException) {
                    Log.w(TAG, "${item.id}: HTTP ${e.code}")
                    if (e.code == 429 || e.code == 503) {
                        throttled++
                        scheduleThrottle(e.retryAfterMs) // pausa toda la cola, persistente
                        waitMs = 0
                    } else {
                        attempt++; waitMs = BACKOFF_MS shl (attempt - 1)
                    }
                    false
                } catch (e: Exception) {
                    attempt++; waitMs = BACKOFF_MS shl (attempt - 1)
                    Log.w(TAG, "${item.id} intento $attempt: ${e.message}")
                    false
                } catch (e: OutOfMemoryError) {
                    attempt++; waitMs = BACKOFF_MS shl (attempt - 1)
                    Log.w(TAG, "${item.id}: sin memoria")
                    false
                }
            }
            if (ok) done++ else Log.w(TAG, "${item.id}: se omite (reintentos agotados)")
            Thread.sleep(PACE_MS)
        }
        Log.i(TAG, "descarga: $done obras nuevas en ${(System.currentTimeMillis() - t0) / 1000}s, progreso ${downloadProgress()}")
    }

    /** Próximo intento permitido (persistido): backoff exponencial 60 s .. 30 min con jitter, o Retry-After si es mayor. */
    private fun scheduleThrottle(retryAfterMs: Long) {
        val level = prefs.getInt(KEY_BACKOFF_LEVEL, 0)
        val base = min(MAX_BACKOFF_MS, MIN_BACKOFF_MS shl min(level, 5))
        val jitter = 0.8 + Random.nextDouble() * 0.4
        val delay = max(retryAfterMs, (base * jitter).toLong()).coerceAtMost(MAX_BACKOFF_MS)
        prefs.edit().putInt(KEY_BACKOFF_LEVEL, level + 1).putLong(KEY_NEXT_TRY, System.currentTimeMillis() + delay).apply()
        Log.w(TAG, "429/503: cola en pausa ${delay / 1000}s (nivel ${level + 1})")
    }

    private fun nextAllowedMs(): Long = prefs.getLong(KEY_NEXT_TRY, 0L)

    /** Bloquea el hilo de descarga hasta que pase el próximo intento permitido (no martilla a Commons). */
    private fun waitForAllowed() {
        while (true) {
            val w = nextAllowedMs() - System.currentTimeMillis()
            if (w <= 0) return
            Thread.sleep(min(w, 30_000L))
        }
    }

    private fun networkAllowed(): Boolean {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
            !cm.isActiveNetworkMetered
    }

    /** Descarga, recorta, compone y guarda una obra. Devuelve false si falla (para reintento). */
    private fun process(item: CatalogItem, dir: File): Boolean {
        val tmp = File(app.cacheDir, "art-dl.tmp")
        try {
            download(item.url, tmp)
            val bmp = render(item, tmp) ?: return false
            val dest = File(dir, item.file)
            val part = File(dir, item.file + ".part")
            try {
                part.outputStream().use { if (!bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)) return false }
            } finally {
                bmp.recycle()
            }
            if (!part.renameTo(dest)) return false
            addToIndex(item, dest.length(), dir)
            return true
        } finally {
            tmp.delete()
        }
    }

    /** Región recortada con BitmapRegionDecoder, escalada a 1920x1080 (o compuesta en passe-partout). */
    private fun render(item: CatalogItem, src: File): Bitmap? {
        val decoder = BitmapRegionDecoder.newInstance(src.absolutePath, false) ?: return null
        try {
            val r = ArtCatalog.cropRect(item.crop, decoder.width, decoder.height)
            val rect = Rect(r.left, r.top, r.right, r.bottom)
            if (!item.mat) {
                val sample = ArtCatalog.sampleSize(r.width, ArtCatalog.OUT_W)
                val region = decoder.decodeRegion(rect, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
                return scaleTo(region, ArtCatalog.OUT_W, ArtCatalog.OUT_H)
            }
            val (aw, ah) = ArtCatalog.matArtSize(r.width, r.height)
            val sample = ArtCatalog.sampleSize(r.width, aw)
            val region = decoder.decodeRegion(rect, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            return composeMat(scaleTo(region, aw, ah))
        } finally {
            decoder.recycle()
        }
    }

    private fun scaleTo(b: Bitmap, w: Int, h: Int): Bitmap {
        if (b.width == w && b.height == h) return b
        val out = Bitmap.createScaledBitmap(b, w, h, true)
        if (out !== b) b.recycle()
        return out
    }

    /** Passe-partout en CPU: papel, sombra del bisel y sombra interior (equivale a mat169 de art/tools/process.py). */
    private fun composeMat(art: Bitmap): Bitmap {
        val W = ArtCatalog.OUT_W
        val H = ArtCatalog.OUT_H
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.rgb(0xED, 0xE8, 0xE0))
        val x = (W - art.width) / 2
        val y = (H - art.height) / 2
        // sombra del bisel: caída suave bajo la obra
        val sh = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(120, 60, 52, 44)
            maskFilter = BlurMaskFilter(14.7f, BlurMaskFilter.Blur.NORMAL)
        }
        c.drawRect(x.toFloat(), (y + 3).toFloat(), (x + art.width).toFloat(), (y + art.height + 3).toFloat(), sh)
        // sombra interior sobre la obra: anillo difuminado dentro de sus bordes
        val a = art.copy(Bitmap.Config.ARGB_8888, true)
        art.recycle()
        val ac = Canvas(a)
        val ring = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            addRect(RectF(-30f, -30f, a.width + 30f, a.height + 30f), Path.Direction.CW)
            addRect(RectF(8f, 10f, a.width - 8f, a.height - 6f), Path.Direction.CW)
        }
        val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(77, 20, 16, 12)
            maskFilter = BlurMaskFilter(11.3f, BlurMaskFilter.Blur.NORMAL)
        }
        ac.drawPath(ring, inner)
        c.drawBitmap(a, x.toFloat(), y.toFloat(), null)
        a.recycle()
        return out
    }

    private class HttpException(val code: Int, val retryAfterMs: Long) : IOException("HTTP $code")

    private fun download(url: String, dest: File) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = IMG_TIMEOUT_MS
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Accept", "image/jpeg,image/*;q=0.8")
            if (conn.responseCode != 200) {
                val ra = conn.getHeaderField("Retry-After")?.trim()?.toLongOrNull() ?: 0L
                throw HttpException(conn.responseCode, (ra * 1000).coerceIn(0L, 60_000L))
            }
            var total = 0L
            conn.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        total += n
                        if (total > MAX_DOWNLOAD) throw IOException("imagen > $MAX_DOWNLOAD bytes")
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Agrega/reemplaza la obra en index.json (mismo formato que el generado en el Mac) con escritura atómica. */
    private fun addToIndex(item: CatalogItem, bytes: Long, dir: File) = synchronized(indexLock) {
        val f = indexFile(dir)
        val existing = try {
            if (f.exists()) JSONArray(f.readText()) else JSONArray()
        } catch (e: Exception) {
            JSONArray()
        }
        val byId = LinkedHashMap<String, JSONObject>()
        for (i in 0 until existing.length()) existing.getJSONObject(i).let { byId[it.getString("id")] = it }
        val o = JSONObject()
            .put("id", item.id)
            .put("title", item.title)
            .put("year", item.year)
            .put("museum", item.museum)
            .put("source_url", item.sourceUrl)
            .put("license", item.license)
            .put("artist", item.artist)
            .put("file", item.file)
            .put("mode", item.mode)
            .put("bytes", bytes)
        if (!item.home) o.put("home", false)
        byId[item.id] = o
        val ordered = JSONArray()
        // orden del catálogo, luego cualquier obra ajena
        val cat = catalog()
        cat.forEach { c -> byId.remove(c.id)?.let { ordered.put(it) } }
        byId.values.forEach { ordered.put(it) }
        val tmp = File(dir, "index.json.tmp")
        tmp.writeText(ordered.toString(1))
        if (!tmp.renameTo(f)) Log.w(TAG, "no se pudo reemplazar index.json")
    }

    companion object {
        private const val TAG = "MuseumArt"
        private const val UA = "Lienzo-TV-Launcher/1.0 (https://github.com/; uso personal)"
        private const val ASSET_INDEX = "art_index.json"
        private const val ONLINE_FLAG = ".online"
        private const val KEY_LAST_LOCAL_HOME = "last_local_home"
        private const val KEY_LAST_LOCAL_ALL = "last_local_all"
        private const val KEY_NEXT_TRY = "art_next_try"
        private const val KEY_BACKOFF_LEVEL = "art_backoff_level"
        private const val MIN_BACKOFF_MS = 60_000L
        private const val MAX_BACKOFF_MS = 30 * 60_000L
        private const val LOCAL_SEED = 585
        private const val TIMEOUT_MS = 10_000
        private const val IMG_TIMEOUT_MS = 30_000
        private const val JPEG_QUALITY = 82
        private const val MAX_DOWNLOAD = 25L * 1024 * 1024
        private const val MAX_ATTEMPTS = 5
        private const val MAX_THROTTLED = 100
        private const val BACKOFF_MS = 2_000L
        private const val PACE_MS = 1_500L

        private val running = AtomicBoolean(false)
        @Volatile private var catalogCache: List<CatalogItem>? = null
    }
}
