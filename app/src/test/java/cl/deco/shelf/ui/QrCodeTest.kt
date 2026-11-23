package cl.deco.shelf.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QrCodeTest {

    private fun assertFinder(m: QrCode.Matrix, ox: Int, oy: Int) {
        for (dy in 0..6) for (dx in 0..6) {
            val expected = dx == 0 || dx == 6 || dy == 0 || dy == 6 || (dx in 2..4 && dy in 2..4)
            assertEquals("finder ($ox,$oy)+($dx,$dy)", expected, m.get(ox + dx, oy + dy))
        }
    }

    @Test fun generaMatrizValidaParaUrlLan() {
        val url = "http://192.168.100.250:54321/?t=a1b2c3d4" // 40 bytes -> versión 3 (29x29)
        val m = QrCode.encode(url)
        assertEquals(29, m.size)
        assertEquals(29 * 29, m.modules.size)
        assertFinder(m, 0, 0)
        assertFinder(m, m.size - 7, 0)
        assertFinder(m, 0, m.size - 7)
        // Módulo oscuro fijo en (8, size - 8)
        assertTrue(m.get(8, m.size - 8))
        // Timing pattern horizontal (y=6, x=8..20)
        for (x in 8 until m.size - 8) assertEquals(x % 2 == 0, m.get(x, 6))
        // Determinismo
        val m2 = QrCode.encode(url)
        assertTrue(m.modules.contentEquals(m2.modules))
    }

    @Test fun soportaUrlsLargasHastaVersion6() {
        val longUrl = "http://192.168.123.123:65535/?t=0123456789abcdef&extra=0123456789abcdef0123456789abcdef"
        val m = QrCode.encode(longUrl)
        assertTrue(m.size in listOf(21, 25, 29, 33, 37, 41))
        assertFinder(m, 0, 0)
        assertFinder(m, m.size - 7, 0)
        assertFinder(m, 0, m.size - 7)
    }

    @Test fun rechazaPayloadExcesivo() {
        var threw = false
        try {
            QrCode.encode("a".repeat(200))
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
        assertFalse(QrCode.encode("http://10.0.0.1:8080/?t=12345678").modules.all { it })
    }
}
