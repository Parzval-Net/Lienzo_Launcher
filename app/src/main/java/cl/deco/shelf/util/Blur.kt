package cl.deco.shelf.util

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Blur precalculado (no en tiempo real). Reduce primero y luego aplica stack blur en CPU
 * (algoritmo de Mario Klingemann, implementación propia). Objetivo: < 60 ms para 1920x1080 en A53.
 */
object Blur {

    /** Devuelve un bitmap ARGB_8888 nuevo, reducido por [downscale]. No modifica [src]. */
    fun blurred(src: Bitmap, radius: Int = 20, downscale: Int = 8): Bitmap {
        val w = max(1, src.width / downscale)
        val h = max(1, src.height / downscale)
        val pixels = scaledArgbPixels(src, w, h)
        stackBlurPixels(pixels, w, h, radius.coerceIn(1, 254))
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * Escala directo a [targetW]x[targetH], aplica stack blur y desatura en un solo paso sin copias
     * intermedias en 960x540 ni tabla de 1 MB. Devuelve RGB_565 opaco (mitad de memoria en grilla).
     */
    fun blurredDesaturated(
        src: Bitmap,
        targetW: Int = 120,
        targetH: Int = 67,
        radius: Int = 20,
        saturation: Float = 0.55f,
    ): Bitmap {
        val w = max(1, targetW)
        val h = max(1, targetH)
        val pixels = scaledArgbPixels(src, w, h)
        stackBlurPixels(pixels, w, h, radius.coerceIn(1, 254))
        for (i in pixels.indices) pixels[i] = desaturatePixel(pixels[i], saturation)
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.RGB_565)
    }

    private fun scaledArgbPixels(src: Bitmap, w: Int, h: Int): IntArray {
        val small = if (src.width == w && src.height == h) src else Bitmap.createScaledBitmap(src, w, h, true)
        val argb = if (small.config == Bitmap.Config.ARGB_8888) small else small.copy(Bitmap.Config.ARGB_8888, false)
        val pixels = IntArray(w * h)
        argb.getPixels(pixels, 0, w, 0, 0, w, h)
        if (argb !== small && argb !== src) argb.recycle()
        if (small !== src) small.recycle()
        return pixels
    }

    internal fun desaturatePixel(rgb: Int, saturation: Float): Int {
        val r = (rgb shr 16) and 0xff
        val g = (rgb shr 8) and 0xff
        val b = rgb and 0xff
        val luma = (77 * r + 150 * g + 29 * b) shr 8
        val nr = (luma + ((r - luma) * saturation + 0.5f).toInt()).coerceIn(0, 255)
        val ng = (luma + ((g - luma) * saturation + 0.5f).toInt()).coerceIn(0, 255)
        val nb = (luma + ((b - luma) * saturation + 0.5f).toInt()).coerceIn(0, 255)
        return -0x1000000 or (nr shl 16) or (ng shl 8) or nb
    }

    internal fun stackBlurPixels(pix: IntArray, w: Int, h: Int, radius: Int) {
        if (w <= 0 || h <= 0) return
        val div = radius * 2 + 1
        val half = (div + 1) shr 1
        val divSum = half * half
        val sr = IntArray(div)
        val sg = IntArray(div)
        val sb = IntArray(div)

        // Pasada horizontal
        for (y in 0 until h) {
            var rsum = 0; var gsum = 0; var bsum = 0
            var rin = 0; var gin = 0; var bin = 0
            var rout = 0; var gout = 0; var bout = 0
            val row = y * w
            for (i in -radius..radius) {
                val p = pix[row + i.coerceIn(0, w - 1)]
                val k = i + radius
                val r = (p shr 16) and 0xff
                val g = (p shr 8) and 0xff
                val b = p and 0xff
                sr[k] = r; sg[k] = g; sb[k] = b
                val weight = half - abs(i)
                rsum += r * weight; gsum += g * weight; bsum += b * weight
                if (i > 0) {
                    rin += r; gin += g; bin += b
                } else {
                    rout += r; gout += g; bout += b
                }
            }
            var sp = radius
            for (x in 0 until w) {
                pix[row + x] = -0x1000000 or ((rsum / divSum) shl 16) or ((gsum / divSum) shl 8) or (bsum / divSum)
                rsum -= rout; gsum -= gout; bsum -= bout
                var k = (sp - radius + div) % div
                rout -= sr[k]; gout -= sg[k]; bout -= sb[k]
                val p = pix[row + min(x + radius + 1, w - 1)]
                val r = (p shr 16) and 0xff
                val g = (p shr 8) and 0xff
                val b = p and 0xff
                sr[k] = r; sg[k] = g; sb[k] = b
                rin += r; gin += g; bin += b
                rsum += rin; gsum += gin; bsum += bin
                sp = (sp + 1) % div
                k = sp
                rout += sr[k]; gout += sg[k]; bout += sb[k]
                rin -= sr[k]; gin -= sg[k]; bin -= sb[k]
            }
        }

        // Pasada vertical
        for (x in 0 until w) {
            var rsum = 0; var gsum = 0; var bsum = 0
            var rin = 0; var gin = 0; var bin = 0
            var rout = 0; var gout = 0; var bout = 0
            for (i in -radius..radius) {
                val p = pix[i.coerceIn(0, h - 1) * w + x]
                val k = i + radius
                val r = (p shr 16) and 0xff
                val g = (p shr 8) and 0xff
                val b = p and 0xff
                sr[k] = r; sg[k] = g; sb[k] = b
                val weight = half - abs(i)
                rsum += r * weight; gsum += g * weight; bsum += b * weight
                if (i > 0) {
                    rin += r; gin += g; bin += b
                } else {
                    rout += r; gout += g; bout += b
                }
            }
            var sp = radius
            for (y in 0 until h) {
                pix[y * w + x] = -0x1000000 or ((rsum / divSum) shl 16) or ((gsum / divSum) shl 8) or (bsum / divSum)
                rsum -= rout; gsum -= gout; bsum -= bout
                var k = (sp - radius + div) % div
                rout -= sr[k]; gout -= sg[k]; bout -= sb[k]
                val p = pix[min(y + radius + 1, h - 1) * w + x]
                val r = (p shr 16) and 0xff
                val g = (p shr 8) and 0xff
                val b = p and 0xff
                sr[k] = r; sg[k] = g; sb[k] = b
                rin += r; gin += g; bin += b
                rsum += rin; gsum += gin; bsum += bin
                sp = (sp + 1) % div
                k = sp
                rout += sr[k]; gout += sg[k]; bout += sb[k]
                rin -= sr[k]; gin -= sg[k]; bin -= sb[k]
            }
        }
    }
}
