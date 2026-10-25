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
 * al dibujar) y las imágenes de las tarjetas del Top Shelf, ambos en RGB_565. Cache LRU de 4 MB acotada por bytes.
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

    /** Pedidos en curso por clave: una misma imagen pedida dos veces (Top Shelf y Continuar viendo) se descarga una sola vez. */
    private val inflight = HashMap<String, MutableList<(Bitmap?) -> Unit>>()

    /**
     * Imagen de tarjeta (cover recortada, esquinas redondeadas y scrim inferior) descargada/decodificada en background.
     * [wanted] se evalúa en el hilo de trabajo justo antes de ir a la red: si la tarjeta ya no se muestra (el foco pasó a otra
     * app) la descarga se omite y la cola no se llena de pedidos viejos.
     */
    fun loadUri(uri: String, cw: Int, ch: Int, wanted: () -> Boolean = { true }, onLoaded: (Bitmap?) -> Unit) {
        val key = uriCacheKey(uri, cw, ch)
        synchronized(inflight) {
            inflight[key]?.let { it += onLoaded; return }
            inflight[key] = mutableListOf(onLoaded)
        }
        pool.execute {
            val bmp = cache.get(key) ?: if (!wanted()) null else try {
                val f = download(uri)
                try {
                    f?.let { BitmapUtil.decodeFile(it.path, cw, ch, Bitmap.Config.ARGB_8888) }?.let { src ->
                        cover(src, cw, ch).also { src.recycle(); cache.put(key, it) }
                    }
                } finally { f?.delete() }
            } catch (e: Throwable) { null }
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
        pool.execute { renderer.clearAll() }
    }

    /** Tipo de tile (banner, adaptive, legacy, override) por clave de app, para diagnóstico. */
    fun kinds(): Map<String, TileRenderer.Kind> = renderer.kinds

    /**
     * Copia la imagen a un archivo temporal en vez de a un ByteArray: el heap Java no crece con cada descarga (antes un
     * póster de 1-4 MB se duplicaba al armar el arreglo) y la decodificación lee el archivo con downsample.
     */
    private fun download(uri: String): java.io.File? {
        val u = Uri.parse(uri)
        var conn: HttpURLConnection? = null
        val tmp = java.io.File.createTempFile("card", null, context.cacheDir)
        var ok = false
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
            conn?.disconnect() // libera el socket aunque la lectura falle o se corte por tamaño
            if (!ok) tmp.delete()
        }
    }

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
        const val MAX_DOWNLOAD = 4 * 1024 * 1024
    }
}

/** Clave de caché de una imagen por URI: incluye el tamaño pedido, porque cada vista la recorta y redondea a su escala. */
internal fun uriCacheKey(uri: String, cw: Int, ch: Int) = "u:$uri:${cw}x$ch"
