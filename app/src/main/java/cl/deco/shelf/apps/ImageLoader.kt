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
import java.util.concurrent.Executors

/**
 * Genera en background el bitmap final de cada tile (fondo, banner/ícono, label, esquinas redondeadas ya
 * aplicadas) y las imágenes de las tarjetas del Top Shelf. Cache LRU de ~8 MB acotada por bytes.
 */
class ImageLoader(private val context: Context, private val w: Int, private val h: Int, private val radius: Float) {

    private val pm = context.packageManager
    private val density = context.resources.displayMetrics.density
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(2)

    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    fun cached(entry: AppEntry): Bitmap? = cache.get(entry.key)

    fun load(entry: AppEntry, onLoaded: (Bitmap) -> Unit) {
        pool.execute {
            val bmp = cache.get(entry.key) ?: render(entry)?.also { cache.put(entry.key, it) }
            if (bmp != null) main.post { onLoaded(bmp) }
        }
    }

    fun cachedUri(uri: String): Bitmap? = cache.get("u:$uri")

    /** Imagen de tarjeta (cover recortada, esquinas redondeadas y scrim inferior) descargada/decodificada en background. */
    fun loadUri(uri: String, cw: Int, ch: Int, onLoaded: (Bitmap?) -> Unit) {
        val key = "u:$uri"
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

    private fun readBytes(uri: String): ByteArray? {
        val u = Uri.parse(uri)
        val stream = if (u.scheme == "http" || u.scheme == "https") {
            val c = URL(uri).openConnection() as HttpURLConnection
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

    /** Color promedio (ponderado por alpha) del drawable, calculado sobre un bitmap diminuto. */
    private fun averageColor(d: Drawable): Int {
        val sw = 16
        val sh = 9
        val small = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        val oldBounds = d.copyBounds()
        d.setBounds(0, 0, sw, sh)
        d.draw(Canvas(small))
        d.bounds = oldBounds
        var r = 0L; var g = 0L; var b = 0L; var n = 0L
        val px = IntArray(sw * sh)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        small.recycle()
        for (p in px) {
            val a = Color.alpha(p)
            if (a < 128) continue
            r += Color.red(p); g += Color.green(p); b += Color.blue(p); n++
        }
        if (n == 0L) return 0xFF1B1E27.toInt()
        return Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    private fun render(entry: AppEntry): Bitmap? = try {
        val info: ActivityInfo = pm.getActivityInfo(entry.component, 0)
        val banner: Drawable? = info.loadBanner(pm)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val path = Path().apply { addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), radius, radius, Path.Direction.CW) }
        c.clipPath(path)
        if (banner != null) {
            // Banner 16:9 contenido en tile 5:3 sobre el color promedio del propio banner
            c.drawColor(averageColor(banner))
            val iw = banner.intrinsicWidth.takeIf { it > 0 } ?: w
            val ih = banner.intrinsicHeight.takeIf { it > 0 } ?: h
            val s = minOf(w / iw.toFloat(), h / ih.toFloat())
            val dw = (iw * s).toInt()
            val dh = (ih * s).toInt()
            val l = (w - dw) / 2
            val t = (h - dh) / 2
            banner.setBounds(l, t, l + dw, t + dh)
            banner.draw(c)
        } else {
            val bg = Paint().apply {
                shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFF2E3340.toInt(), 0xFF1A1D26.toInt(), Shader.TileMode.CLAMP)
            }
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)
            val icon = info.loadIcon(pm)
            val side = (h * 0.5f).toInt()
            val l = (w - side) / 2
            val t = (h * 0.12f).toInt()
            icon.setBounds(l, t, l + side, t + side)
            icon.draw(c)
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 12f * density
                textAlign = Paint.Align.CENTER
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            }
            val maxW = w - 16f * density
            val text = TextUtils.ellipsize(entry.label, tp, maxW, TextUtils.TruncateAt.END).toString()
            c.drawText(text, w / 2f, h - 14f * density, tp)
        }
        bmp
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val CACHE_BYTES = 8 * 1024 * 1024
        const val MAX_DOWNLOAD = 4 * 1024 * 1024
    }
}
