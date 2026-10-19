package cl.deco.shelf.apps

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/** Decodificación con downsample (inSampleSize) y RGB_565 para ahorrar RAM. */
object BitmapUtil {

    private fun sampleFor(w: Int, h: Int, reqW: Int, reqH: Int): Int {
        var s = 1
        while (w / (s * 2) >= reqW && h / (s * 2) >= reqH) s *= 2
        return s
    }

    fun decodeBytes(bytes: ByteArray, reqW: Int, reqH: Int, config: Bitmap.Config = Bitmap.Config.RGB_565): Bitmap? {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, b)
        if (b.outWidth <= 0) return null
        val o = BitmapFactory.Options().apply {
            inSampleSize = sampleFor(b.outWidth, b.outHeight, reqW, reqH)
            inPreferredConfig = config
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
    }

    /** Imagen de fondo: decodifica y reduce a lo más [maxW] de ancho. */
    fun decodeFile(path: String, maxW: Int, maxH: Int): Bitmap? {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, b)
        if (b.outWidth <= 0) return null
        val o = BitmapFactory.Options().apply {
            inSampleSize = sampleFor(b.outWidth, b.outHeight, maxW, maxH)
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        val bmp = BitmapFactory.decodeFile(path, o) ?: return null
        if (bmp.width <= maxW * 1.15f) return bmp
        val nh = (bmp.height.toLong() * maxW / bmp.width).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bmp, maxW, nh, true)
        if (scaled !== bmp) bmp.recycle()
        return scaled
    }

    /** Último fondo bueno ya escalado (JPEG q87) para pintar el arranque en frío sin flash negro. */
    fun saveJpeg(b: Bitmap, f: java.io.File) {
        try {
            val tmp = java.io.File(f.parentFile, f.name + ".tmp")
            tmp.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 87, it) }
            tmp.renameTo(f)
        } catch (_: Exception) {}
    }

    fun loadJpeg565(f: java.io.File): Bitmap? =
        if (!f.exists()) null else BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })

    /**
     * Fondo exacto de [w]x[h] en RGB_565 (recorte centrado a 16:9, una sola copia en memoria) y mutable para
     * hornear el scrim inferior. Así la GPU no necesita escalar ni mezclar una capa extra a pantalla completa.
     */
    fun coverFile(path: String, w: Int, h: Int, bake: (Bitmap) -> Unit): Bitmap? {
        val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, b)
        if (b.outWidth <= 0) return null
        val src = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
            inSampleSize = sampleFor(b.outWidth, b.outHeight, w, h)
            inPreferredConfig = Bitmap.Config.ARGB_8888 // origen completo; el dithering se aplica al bajar a 565
        }) ?: return null
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        val s = maxOf(w / src.width.toFloat(), h / src.height.toFloat())
        val cw = (w / s).toInt().coerceAtMost(src.width); val ch = (h / s).toInt().coerceAtMost(src.height)
        val l = (src.width - cw) / 2; val t = (src.height - ch) / 2
        android.graphics.Canvas(out).drawBitmap(
            src, android.graphics.Rect(l, t, l + cw, t + ch), android.graphics.Rect(0, 0, w, h),
            android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.DITHER_FLAG), // dither: evita banding en cielos y degradados suaves
        )
        src.recycle()
        bake(out)
        return out
    }
}
