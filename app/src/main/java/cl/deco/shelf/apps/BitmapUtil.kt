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
}
