package cl.deco.shelf.util

import android.graphics.Bitmap
import android.graphics.Color

/** Paleta simple de la obra: acento saturado, versión clara y versión oscura (texto blanco >= 4,5:1 sobre ella). */
class Palette(val accent: Int, val light: Int, val dark: Int) {

    companion object {
        /** k-means propio de 5 colores sobre el bitmap reducido a 64x36. Llamar fuera del main thread. */
        fun from(src: Bitmap): Palette {
            val small = Bitmap.createScaledBitmap(src, 64, 36, true)
            val px = IntArray(64 * 36)
            small.getPixels(px, 0, 64, 0, 0, 64, 36)
            if (small !== src) small.recycle()
            return fromPixels(px)
        }

        fun fromPixels(px: IntArray): Palette {
            val k = 5
            val n = px.size
            val cr = FloatArray(k); val cg = FloatArray(k); val cb = FloatArray(k)
            for (i in 0 until k) {
                val p = px[(i * n / k + n / (2 * k)).coerceIn(0, n - 1)]
                cr[i] = Color.red(p).toFloat(); cg[i] = Color.green(p).toFloat(); cb[i] = Color.blue(p).toFloat()
            }
            val assign = IntArray(n)
            val cnt = IntArray(k)
            repeat(8) {
                val sr = FloatArray(k); val sg = FloatArray(k); val sb = FloatArray(k)
                cnt.fill(0)
                for (i in 0 until n) {
                    val r = Color.red(px[i]); val g = Color.green(px[i]); val b = Color.blue(px[i])
                    var best = 0; var bd = Float.MAX_VALUE
                    for (c in 0 until k) {
                        val dr = r - cr[c]; val dg = g - cg[c]; val db = b - cb[c]
                        val d = dr * dr + dg * dg + db * db
                        if (d < bd) { bd = d; best = c }
                    }
                    assign[i] = best; cnt[best]++
                    sr[best] += r; sg[best] += g; sb[best] += b
                }
                for (c in 0 until k) if (cnt[c] > 0) { cr[c] = sr[c] / cnt[c]; cg[c] = sg[c] / cnt[c]; cb[c] = sb[c] / cnt[c] }
            }
            // Dominante saturado: tamaño del cluster ponderado por saturación, descartando grises y casi negros
            var bestScore = -1f; var bestHsv = floatArrayOf(25f, 0.7f, 0.9f)
            val hsv = FloatArray(3)
            for (c in 0 until k) {
                if (cnt[c] == 0) continue
                Color.RGBToHSV(cr[c].toInt(), cg[c].toInt(), cb[c].toInt(), hsv)
                val score = (cnt[c] / n.toFloat()) * (0.15f + hsv[1]) * (if (hsv[2] < 0.15f) 0.2f else 1f)
                if (score > bestScore) { bestScore = score; bestHsv = hsv.copyOf() }
            }
            val h = bestHsv[0]
            val s = bestHsv[1].coerceIn(0.55f, 0.90f)
            val accent = Color.HSVToColor(floatArrayOf(h, s, bestHsv[2].coerceIn(0.78f, 1f)))
            val light = Color.HSVToColor(floatArrayOf(h, s * 0.45f, 1f))
            var v = 0.22f
            var dark = Color.HSVToColor(floatArrayOf(h, (s * 0.8f).coerceAtMost(0.8f), v))
            while (contrastWithWhite(dark) < 4.5 && v > 0.02f) {
                v -= 0.02f
                dark = Color.HSVToColor(floatArrayOf(h, (s * 0.8f).coerceAtMost(0.8f), v))
            }
            return Palette(accent, light, dark)
        }

        private fun lin(c: Int): Double {
            val x = c / 255.0
            return if (x <= 0.03928) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4)
        }

        fun luminance(c: Int) = 0.2126 * lin(Color.red(c)) + 0.7152 * lin(Color.green(c)) + 0.0722 * lin(Color.blue(c))

        /** Contraste WCAG del blanco sobre [c]. */
        fun contrastWithWhite(c: Int) = 1.05 / (luminance(c) + 0.05)
    }
}
