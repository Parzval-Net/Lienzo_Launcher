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

    @Test fun obrasDeTemporada() {
        val obras = listOf("monet" to "", "fuseli" to "halloween", "degas" to "")
        assertEquals(listOf("fuseli" to "halloween"), Seasonal.pick(obras, "halloween") { it.second })
        assertEquals(listOf("monet" to "", "degas" to ""), Seasonal.pick(obras, null) { it.second })
        // En temporada pero sin obras de temporada descargadas: sigue la colección normal
        assertEquals(listOf("monet" to ""), Seasonal.pick(listOf("monet" to ""), "halloween") { it.second })
    }

    @Test fun saludo() {
        assertEquals("Noche de Halloween", Seasonal.greeting(LocalDate.of(2026, 10, 31), 21))
        assertNull(Seasonal.greeting(LocalDate.of(2026, 10, 31), 12))
        assertNull(Seasonal.greeting(LocalDate.of(2026, 10, 30), 21))
    }
}
