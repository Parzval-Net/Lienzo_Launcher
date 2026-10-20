package cl.deco.shelf.data.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SunDimTest {
    private fun day(d: Int, rise: Long, set: Long) = DayForecast(LocalDate.of(2026, 10, d), 5.0, 20.0, 0, null, rise, set)

    private val days = listOf(day(19, 1000, 5000), day(20, 7000, 11000))

    @Test fun diaEntreAmanecerYAtardecer() {
        val s = SunDim.state(days, 3000)!!
        assertFalse(s.night); assertEquals(5000L, s.nextChange)
    }

    @Test fun nocheEntreAtardecerYAmanecer() {
        val s = SunDim.state(days, 6000)!!
        assertTrue(s.night); assertEquals(7000L, s.nextChange)
    }

    @Test fun antesDelPrimerEventoEsNocheSiElPrimeroEsAmanecer() {
        assertTrue(SunDim.state(days, 10)!!.night)
    }

    @Test fun sinDatosNoHayEstado() { assertNull(SunDim.state(listOf(DayForecast(LocalDate.now(), 0.0, 1.0, 0, null)), 1)) }

    @Test fun despuesDelUltimoEventoSinProximo() {
        val s = SunDim.state(days, 20000)!!
        assertTrue(s.night); assertNull(s.nextChange)
    }
}
