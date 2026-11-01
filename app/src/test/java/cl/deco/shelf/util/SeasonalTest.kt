package cl.deco.shelf.util

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SeasonalTest {
    @Test fun ventana() {
        assertFalse(Seasonal.halloween(LocalDate.of(2026, 10, 23)))
        assertTrue(Seasonal.halloween(LocalDate.of(2026, 10, 24)))
        assertTrue(Seasonal.halloween(LocalDate.of(2026, 10, 31)))
        assertTrue(Seasonal.halloween(LocalDate.of(2026, 11, 1)))
        assertFalse(Seasonal.halloween(LocalDate.of(2026, 11, 2)))
        assertFalse(Seasonal.halloween(LocalDate.of(2026, 3, 31)))
    }

    @Test fun ajusteApagado() {
        assertNull(Seasonal.paletteFor(LocalDate.of(2026, 10, 31), false))
        assertEquals(Seasonal.HALLOWEEN, Seasonal.paletteFor(LocalDate.of(2026, 10, 31), true))
        assertNull(Seasonal.paletteFor(LocalDate.of(2026, 11, 2), true))
    }

    @Test fun fondosEnElApk() {
        val d = java.io.File("src/main/assets/seasonal/halloween")
        assertEquals(8, (1..8).count { java.io.File(d, "%03d.jpg".format(it)).length() > 0 })
        assertTrue(java.io.File(d, "CREDITS.md").exists())
    }

    @Test fun saludo() {
        assertEquals("Noche de Halloween", Seasonal.greeting(LocalDate.of(2026, 10, 31), 21))
        assertNull(Seasonal.greeting(LocalDate.of(2026, 10, 31), 12))
        assertNull(Seasonal.greeting(LocalDate.of(2026, 10, 30), 21))
    }
}
