package cl.deco.shelf.apps

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Genera en background el bitmap final de cada tile (fondo, banner/ícono, label, esquinas redondeadas ya
 * al dibujar) y las imágenes de las tarjetas del Top Shelf, ambos en RGB_565. Cache LRU de 4 MB en RAM
 * y caché en disco de tarjetas de hasta 8 MB.
 */
class ImageLoader(private val context: Context, private val w: Int, private val h: Int, private val radius: Float) {

    private val renderer = TileRenderer(context, w, h, radius)
    private val prefs = cl.deco.shelf.settings.ShelfPrefs(context)
    private val main = Handler(Looper.getMainLooper())
    private val cardsDir = File(context.cacheDir, "cards")
    // 2 hilos que mueren tras 10 s ociosos: en reposo el loader no retiene ningún hilo (cada uno reserva ~230 KB de stack)
    private val pool = ThreadPoolExecutor(2, 2, 10L, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>()).apply { allowCoreThreadTimeOut(true) }

    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    fun cached(entry: AppEntry): Bitmap? = cache.get(entry.key)

    fun load(entry: AppEntry, onLoaded: (Bitmap) -> Unit) {
        pool.execute {
            val bmp = cache.get(entry.key) ?: renderer.tile(entry, prefs.iconOriginal)?.also { cache.put(entry.key, it) }
            if (bmp != null) main.post { onLoaded(bmp) }
        }
    }

    fun cachedUri(uri: String, cw: Int, ch: Int): Bitmap? = cache.get(uriCacheKey(uri, cw, ch))

    /** Pedidos en curso por clave: una misma imagen pedida dos veces (Top Shelf y Continuar viendo) se descarga una sola vez. */
    private val inflight = HashMap<String, MutableList<(Bitmap?) -> Unit>>()

    /**
     * Imagen de tarjeta (cover recortada, esquinas redondeadas y scrim inferior) desde disco o descargada en background.
     * [wanted] se evalúa en el hilo de trabajo justo antes de ir a disco o red.
     */
    fun loadUri(uri: String, cw: Int, ch: Int, wanted: () -> Boolean = { true }, onLoaded: (Bitmap?) -> Unit) {
        val key = uriCacheKey(uri, cw, ch)
        synchronized(inflight) {
            inflight[key]?.let { it += onLoaded; return }
            inflight[key] = mutableListOf(onLoaded)
        }
        pool.execute {
            val bmp = cache.get(key) ?: if (!wanted() || !isSafeCardUri(uri)) null else {
                val disk = cardFile(key)
                val fromDisk = if (disk.length() > 0L) {
                    disk.setLastModified(System.currentTimeMillis())
                    BitmapUtil.loadJpeg565(disk)?.also { cache.put(key, it) }
                } else null
                fromDisk ?: try {
                    val f = download(uri)
                    try {
                        f?.let { BitmapUtil.decodeFile(it.path, cw, ch, Bitmap.Config.RGB_565) }?.let { src ->
                            cover(src, cw, ch).also { card ->
                                src.recycle()
                                BitmapUtil.saveWebp(card, disk, 85)
                                pruneCardCache()
                                cache.put(key, card)
                            }
                        }
                    } finally { f?.delete() }
                } catch (_: Throwable) { null }
            }
            val cbs = synchronized(inflight) { inflight.remove(key) }.orEmpty()
            main.post { for (cb in cbs) cb(bmp) }
        }
    }

    fun shutdown() {
        pool.shutdownNow()
        cache.evictAll()
    }

    fun trim() = cache.evictAll()

    /**
     * Recorta la caché a [fraction] de su tope conservando lo más reciente (tiles del dock y tarjetas en pantalla): al
     * volver de una app nada se redibuja ni se vuelve a descargar. Con presión de memoria real se usa [trim].
     */
    fun trimTo(fraction: Float) = cache.trimToSize((CACHE_BYTES * fraction).toInt())

    /** Un paquete cambió (reemplazo o baja): borra su tile en disco y memoria para regenerarlo con el arte nuevo. */
    fun invalidate(pkg: String) {
        pool.execute { renderer.invalidate(pkg) }
        for (k in cache.snapshot().keys) if (k.startsWith("$pkg/")) cache.remove(k)
    }

    /** "Regenerar íconos" o cambio de estilo: borra toda la cache de tiles. */
    fun regenerateAll() {
        cache.evictAll()
        pool.execute {
            renderer.clearAll()
            cardsDir.listFiles()?.forEach { it.delete() }
        }
    }

    /** Tipo de tile (banner, adaptive, legacy, override) por clave de app, para diagnóstico. */
    fun kinds(): Map<String, TileRenderer.Kind> = renderer.kinds

    private fun cardFile(key: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }.take(32)
        return File(cardsDir, "$hex.webp")
    }

    @Synchronized
    private fun pruneCardCache() {
        val files = cardsDir.listFiles() ?: return
        var total = files.sumOf { it.length() }
        if (total <= DISK_CACHE_BYTES) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= DISK_CACHE_TARGET) break
            val len = f.length()
            if (f.delete()) total -= len
        }
    }

    /**
     * Copia la imagen a un archivo temporal sin ByteArray intermedio. Rechaza http://, redes locales y redirects inseguros.
     */
    private fun download(uri: String): File? {
        if (!isSafeCardUri(uri)) return null
        val tmp = File.createTempFile("card", null, context.cacheDir)
        var ok = false
        try {
            val stream = if (uri.startsWith("https://", ignoreCase = true)) {
                openHttpsStream(uri)
            } else {
                context.contentResolver.openInputStream(Uri.parse(uri))
            } ?: return null
            stream.use { s ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(16 * 1024)
                    var total = 0
                    while (true) {
                        val n = s.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_DOWNLOAD) return null
                        out.write(buf, 0, n)
                    }
                }
            }
            ok = true
            return tmp
        } finally {
            if (!ok) tmp.delete()
        }
    }

    private fun openHttpsStream(startUrl: String): java.io.InputStream? {
        var current = startUrl
        repeat(4) {
            if (!isSafeCardUri(current)) return null
            val url = URL(current)
            val host = url.host ?: return null
            if (!resolvesPublicly(host)) return null
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 8000
                instanceFollowRedirects = false
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location")
                conn.disconnect()
                if (loc.isNullOrBlank()) return null
                current = URL(url, loc).toString()
            } else if (code in 200..299) {
                val raw = conn.inputStream
                return object : java.io.FilterInputStream(raw) {
                    override fun close() {
                        try { super.close() } finally { conn.disconnect() }
                    }
                }
            } else {
                conn.disconnect()
                return null
            }
        }
        return null
    }

    private fun resolvesPublicly(host: String): Boolean = try {
        val addrs = InetAddress.getAllByName(host)
        addrs.isNotEmpty() && addrs.none {
            it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress ||
                it.isSiteLocalAddress || it.isMulticastAddress
        }
    } catch (_: Exception) { false }

    /** Tarjeta opaca en RGB_565 (cover centrado y scrim inferior, con dither); las esquinas las pone RoundImageView. */
    private fun cover(src: Bitmap, cw: Int, ch: Int): Bitmap {
        val out = Bitmap.createBitmap(cw, ch, Bitmap.Config.RGB_565)
        val c = Canvas(out)
        val s = maxOf(cw / src.width.toFloat(), ch / src.height.toFloat())
        val m = Matrix().apply {
            setScale(s, s)
            postTranslate((cw - src.width * s) / 2f, (ch - src.height * s) / 2f)
        }
        c.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG))
        c.drawRect(0f, 0f, cw.toFloat(), ch.toFloat(), Paint(Paint.DITHER_FLAG).apply {
            shader = LinearGradient(0f, ch * 0.45f, 0f, ch.toFloat(), 0x00000000, 0xDD000000.toInt(), Shader.TileMode.CLAMP)
        })
        return out
    }

    private companion object {
        const val HIDDEN_CLASS = "hidden"
        // Tiles y tarjetas en RGB_565: 4 MB guardan más imágenes que los 6 MB anteriores en ARGB
        const val CACHE_BYTES = 4 * 1024 * 1024
        const val DISK_CACHE_BYTES = 8L * 1024 * 1024
        const val DISK_CACHE_TARGET = 6L * 1024 * 1024
        const val MAX_DOWNLOAD = 4 * 1024 * 1024
    }
}

/** Clave de caché de una imagen por URI: incluye el tamaño pedido, porque cada vista la recorta y redondea a su escala. */
internal fun uriCacheKey(uri: String, cw: Int, ch: Int) = "u:$uri:${cw}x$ch"

/** Permite solo `content://`, `android.resource://` y `https://` hacia hosts públicos (sin LAN ni loopback). */
internal fun isSafeCardUri(uri: String): Boolean {
    val parsed = try { URI(uri.trim()) } catch (_: Exception) { return false }
    val scheme = parsed.scheme?.lowercase() ?: return false
    if (scheme == "content" || scheme == "android.resource") return true
    if (scheme != "https") return false
    val host = parsed.host?.lowercase()?.trimEnd('.')?.removePrefix("[")?.removeSuffix("]") ?: return false
    if (host.isEmpty()) return false
    if (host == "localhost" || host.endsWith(".local") || host.endsWith(".lan") ||
        host.endsWith(".internal") || host.endsWith(".home.arpa")
    ) return false
    if (isPrivateOrLocalLiteral(host)) return false
    return true
}

private fun isPrivateOrLocalLiteral(host: String): Boolean {
    if (host == "::1" || host == "::" || host.startsWith("fe80:") || host.startsWith("fc") || host.startsWith("fd")) return true
    val parts = host.split('.')
    if (parts.size != 4) return false
    val oct = parts.map { it.toIntOrNull() ?: return false }
    if (oct.any { it !in 0..255 }) return false
    val a = oct[0]; val b = oct[1]
    return a == 0 || a == 10 || a == 127 ||
        (a == 169 && b == 254) ||
        (a == 172 && b in 16..31) ||
        (a == 192 && b == 168) ||
        (a == 100 && b in 64..127)
}
