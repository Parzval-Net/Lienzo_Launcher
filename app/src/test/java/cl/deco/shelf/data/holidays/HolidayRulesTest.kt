package cl.deco.shelf.data.holidays

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class HolidayRulesTest {

    @Test
    fun pascuaConocida() {
        assertEquals(LocalDate.of(2024, 3, 31), HolidayRules.easterSunday(2024))
        assertEquals(LocalDate.of(2025, 4, 20), HolidayRules.easterSunday(2025))
        assertEquals(LocalDate.of(2026, 4, 5), HolidayRules.easterSunday(2026))
        assertEquals(LocalDate.of(2027, 3, 28), HolidayRules.easterSunday(2027))
    }

    /** Nager.Date 2026/CL sin feriados regionales (Asalto y Toma del Morro de Arica). Verificado 2026-10-19. */
    @Test
    fun fallback2026CoincideConNager() {
        assertEquals(
            listOf(
                "2026-01-01", "2026-04-03", "2026-04-04", "2026-05-01", "2026-05-21", "2026-06-21",
                "2026-06-29", "2026-07-16", "2026-08-15", "2026-09-18", "2026-09-19", "2026-10-12",
                "2026-10-31", "2026-11-01", "2026-12-08", "2026-12-25",
            ),
            HolidayRules.forYear(2026).map { it.date.toString() },
        )
    }

    /** Nager.Date 2027/CL: traslados 29 jun -> 28 jun y 12 oct -> 11 oct (ambos martes). */
    @Test
    fun fallback2027CoincideConNager() {
        assertEquals(
            listOf(
                "2027-01-01", "2027-03-26", "2027-03-27", "2027-05-01", "2027-05-21", "2027-06-21",
                "2027-06-28", "2027-07-16", "2027-08-15", "2027-09-18", "2027-09-19", "2027-10-11",
                "2027-10-31", "2027-11-01", "2027-12-08", "2027-12-25",
            ),
            HolidayRules.forYear(2027).map { it.date.toString() },
        )
    }

    @Test
    fun irrenunciablesSegunLey19973() {
        val irr = HolidayRules.forYear(2026).filter { it.irrenunciable }.map { it.date.toString() }
        assertEquals(listOf("2026-01-01", "2026-05-01", "2026-09-18", "2026-09-19", "2026-12-25"), irr)
    }

    @Test
    fun trasladoLey19668() {
        // Martes, miércoles y jueves: lunes de la misma semana
        assertEquals(LocalDate.of(2025, 6, 30), HolidayRules.trasladoLey19668(LocalDate.of(2025, 7, 1)))
        assertEquals(LocalDate.of(2024, 10, 14), HolidayRules.trasladoLey19668(LocalDate.of(2024, 10, 16)))
        assertEquals(LocalDate.of(2024, 10, 14), HolidayRules.trasladoLey19668(LocalDate.of(2024, 10, 17)))
        // Viernes: lunes de la semana siguiente
        assertEquals(LocalDate.of(2024, 6, 3), HolidayRules.trasladoLey19668(LocalDate.of(2024, 5, 31)))
        // Lunes, sábado y domingo: sin traslado
        assertEquals(LocalDate.of(2026, 6, 29), HolidayRules.trasladoLey19668(LocalDate.of(2026, 6, 29)))
        assertEquals(LocalDate.of(2025, 10, 12), HolidayRules.trasladoLey19668(LocalDate.of(2025, 10, 12)))
        assertEquals(LocalDate.of(2025, 10, 13), HolidayRules.trasladoLey19668(LocalDate.of(2025, 10, 14)))
    }

    @Test
    fun trasladoLey20299() {
        // Miércoles: viernes de la misma semana
        assertEquals(LocalDate.of(2029, 11, 2), HolidayRules.trasladoLey20299(LocalDate.of(2029, 10, 31)))
        // Martes (31 oct 2023): viernes de la semana anterior
        assertEquals(LocalDate.of(2023, 10, 27), HolidayRules.trasladoLey20299(LocalDate.of(2023, 10, 31)))
        // Jueves y sábado: sin traslado
        assertEquals(LocalDate.of(2026, 10, 31), HolidayRules.trasladoLey20299(LocalDate.of(2026, 10, 31)))
    }

    @Test
    fun ordenadoPorFecha() {
        val dates = HolidayRules.forYear(2026).map { it.date }
        assertEquals(dates.sorted(), dates)
        assertTrue(HolidayRules.isIrrenunciable(LocalDate.of(2026, 12, 25)))
        assertFalse(HolidayRules.isIrrenunciable(LocalDate.of(2026, 6, 21)))
    }
}
