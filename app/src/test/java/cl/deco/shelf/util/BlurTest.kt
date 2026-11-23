package cl.deco.shelf.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlurTest {

    @Test fun colorUniformeSeConservaTrasBlur() {
        val w = 24
        val h = 16
        val color = 0xFF336699.toInt()
        val pix = IntArray(w * h) { color }
        Blur.stackBlurPixels(pix, w, h, 8)
        for (p in pix) assertEquals(color, p)
    }

    @Test fun blurReduceContrasteExtremo() {
        val w = 20
        val h = 10
        val pix = IntArray(w * h) { i -> if (i % w < w / 2) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }
        Blur.stackBlurPixels(pix, w, h, 4)
        val centerLeft = (pix[5 * w + (w / 2 - 1)] shr 16) and 0xff
        val centerRight = (pix[5 * w + (w / 2)] shr 16) and 0xff
        assertTrue(centerLeft in 90..200)
        assertTrue(centerRight in 55..165)
    }

    @Test fun desaturacionReduceDiferenciaEntreCanales() {
        val pureRed = 0xFFFF0000.toInt()
        val half = Blur.desaturatePixel(pureRed, 0.5f)
        val r = (half shr 16) and 0xff
        val g = (half shr 8) and 0xff
        val b = half and 0xff
        assertTrue(r in 150..180)
        assertTrue(g in 30..45)
        assertEquals(g, b)
        assertEquals(0xFF000000.toInt(), half and 0xFF000000.toInt())
        val gray = Blur.desaturatePixel(pureRed, 0f)
        assertEquals((gray shr 16) and 0xff, (gray shr 8) and 0xff)
        assertEquals((gray shr 8) and 0xff, gray and 0xff)
    }
}
