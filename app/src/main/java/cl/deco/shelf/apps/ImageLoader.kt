package cl.deco.shelf.apps

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.util.concurrent.Executors

/** Carga banner (o ícono) en background con cache LRU acotada por bytes (low RAM). */
class ImageLoader(context: Context, private val w: Int, private val h: Int) {

    private val pm = context.packageManager
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

    fun shutdown() {
        pool.shutdownNow()
        cache.evictAll()
    }

    fun trim() = cache.evictAll()

    private fun render(entry: AppEntry): Bitmap? = try {
        val info: ActivityInfo = pm.getActivityInfo(entry.component, 0)
        val d: Drawable = info.loadBanner(pm) ?: info.loadIcon(pm)
        val isBanner = info.loadBanner(pm) != null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (isBanner) {
            d.setBounds(0, 0, w, h)
        } else {
            val side = (h * 0.7f).toInt()
            val l = (w - side) / 2
            val t = (h - side) / 2
            d.setBounds(l, t, l + side, t + side)
        }
        d.draw(c)
        bmp
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val CACHE_BYTES = 6 * 1024 * 1024
    }
}
