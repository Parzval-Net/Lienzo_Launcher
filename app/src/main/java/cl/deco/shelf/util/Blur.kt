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
        val argb = if (src.config == Bitmap.Config.ARGB_8888) src
        else src.copy(Bitmap.Config.ARGB_8888, false)
        val w = max(1, argb.width / downscale)
        val h = max(1, argb.height / downscale)
        val small = Bitmap.createScaledBitmap(argb, w, h, true)
        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        if (small !== argb) small.recycle()
        if (argb !== src) argb.recycle()
        stackBlur(pixels, w, h, radius.coerceIn(1, 254))
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun stackBlur(pix: IntArray, w: Int, h: Int, radius: Int) {
        val div = radius * 2 + 1
        val half = (div + 1) shr 1
        val divSum = half * half
        val dv = IntArray(256 * divSum) { it / divSum }
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
                pix[row + x] = -0x1000000 or (dv[rsum] shl 16) or (dv[gsum] shl 8) or dv[bsum]
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
                pix[y * w + x] = -0x1000000 or (dv[rsum] shl 16) or (dv[gsum] shl 8) or dv[bsum]
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
