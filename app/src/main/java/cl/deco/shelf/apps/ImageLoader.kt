package cl.deco.shelf.apps

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.text.TextUtils
import android.util.LruCache
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Genera en background el bitmap final de cada tile (fondo, banner/ícono, label, esquinas redondeadas ya
 * aplicadas) y las imágenes de las tarjetas del Top Shelf. Cache LRU de ~8 MB acotada por bytes.
 */
class ImageLoader(private val context: Context, private val w: Int, private val h: Int, private val radius: Float) {

    private val renderer = TileRenderer(context, w, h, radius)
    private val prefs = cl.deco.shelf.settings.ShelfPrefs(context)
    private val main = Handler(Looper.getMainLooper())
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

    /** Imagen de tarjeta (cover recortada, esquinas redondeadas y scrim inferior) descargada/decodificada en background. */
    fun loadUri(uri: String, cw: Int, ch: Int, onLoaded: (Bitmap?) -> Unit) {
        val key = uriCacheKey(uri, cw, ch)
        pool.execute {
            val bmp = cache.get(key) ?: try {
                readBytes(uri)?.let { BitmapUtil.decodeBytes(it, cw, ch) }?.let { src ->
                    roundedCover(src, cw, ch, radius, scrim = true).also { src.recycle(); cache.put(key, it) }
                }
            } catch (e: Throwable) { null }
            main.post { onLoaded(bmp) }
        }
    }

    fun shutdown() {
        pool.shutdownNow()
        cache.evictAll()
    }

    fun trim() = cache.evictAll()

    /** Un paquete cambió (reemplazo o baja): borra su tile en disco y memoria para regenerarlo con el arte nuevo. */
    fun invalidate(pkg: String) {
        pool.execute { renderer.invalidate(pkg) }
        for (k in cache.snapshot().keys) if (k.startsWith("$pkg/")) cache.remove(k)
    }

    /** "Regenerar íconos" o cambio de estilo: borra toda la cache de tiles. */
    fun regenerateAll() {
        cache.evictAll()
        pool.execute { renderer.clearAll() }
    }

    /** Tipo de tile (banner, adaptive, legacy, override) por clave de app, para diagnóstico. */
    fun kinds(): Map<String, TileRenderer.Kind> = renderer.kinds

    private fun readBytes(uri: String): ByteArray? {
        val u = Uri.parse(uri)
        var conn: HttpURLConnection? = null
        try {
            val stream = if (u.scheme == "http" || u.scheme == "https") {
                val c = URL(uri).openConnection() as HttpURLConnection
                conn = c
                c.connectTimeout = 6000
                c.readTimeout = 8000
                c.inputStream
            } else {
                context.contentResolver.openInputStream(u)
            } ?: return null
            stream.use { s ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_DOWNLOAD) return null
                }
                return out.toByteArray()
            }
        } finally {
            conn?.disconnect() // libera el socket aunque la lectura falle o se corte por tamaño
        }
    }

    private fun roundedCover(src: Bitmap, cw: Int, ch: Int, r: Float, scrim: Boolean): Bitmap {
        val out = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val s = maxOf(cw / src.width.toFloat(), ch / src.height.toFloat())
        val m = Matrix().apply {
            setScale(s, s)
            postTranslate((cw - src.width * s) / 2f, (ch - src.height * s) / 2f)
        }
        val shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(m) }
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader }
        c.drawRoundRect(RectF(0f, 0f, cw.toFloat(), ch.toFloat()), r, r, p)
        if (scrim) {
            val sp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.shader = LinearGradient(0f, ch * 0.45f, 0f, ch.toFloat(), 0x00000000, 0xDD000000.toInt(), Shader.TileMode.CLAMP)
            }
            c.drawRoundRect(RectF(0f, 0f, cw.toFloat(), ch.toFloat()), r, r, sp)
        }
        return out
    }

    private companion object {
        const val HIDDEN_CLASS = "hidden"
        const val CACHE_BYTES = 6 * 1024 * 1024
        const val MAX_DOWNLOAD = 4 * 1024 * 1024
    }
}

/** Clave de caché de una imagen por URI: incluye el tamaño pedido, porque cada vista la recorta y redondea a su escala. */
internal fun uriCacheKey(uri: String, cw: Int, ch: Int) = "u:$uri:${cw}x$ch"
