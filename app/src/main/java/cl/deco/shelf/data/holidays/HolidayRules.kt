package cl.deco.shelf.data.holidays

import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Feriados de Chile calculados sin red (fallback offline).
 * Fuentes normativas:
 * - Ley 19.668 (DO 10-03-2000): traslado a lunes del 29 jun y 12 oct; si cae martes, miércoles o jueves,
 *   lunes de la misma semana; si cae viernes, lunes de la semana siguiente.
 * - Ley 20.299 (DO 11-10-2008) art. 2: 31 oct se traslada a viernes (miércoles: viernes siguiente;
 *   martes: viernes anterior).
 * - Ley 19.973 art. 2: irrenunciables 1 ene, 1 may, 18 sep, 19 sep, 25 dic (vía DerechoPedia; BCN pendiente).
 * - Ley 21.357 (DO 19-06-2021): solsticio de invierno. Se aproxima al 21 jun; la API primaria manda.
 * Validado contra Nager.Date 2026 y 2027 (ver HolidayRulesTest).
 */
object HolidayRules {

    /** Domingo de Pascua, algoritmo anónimo gregoriano de Meeus/Jones/Butcher. */
    fun easterSunday(year: Int): LocalDate {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = ((h + l - 7 * m + 114) % 31) + 1
        return LocalDate.of(year, month, day)
    }

    /** Irrenunciables según Ley 19.973 art. 2. */
    fun isIrrenunciable(date: LocalDate): Boolean = when (date.monthValue to date.dayOfMonth) {
        1 to 1, 5 to 1, 9 to 18, 9 to 19, 12 to 25 -> true
        else -> false
    }

    /** Ley 19.668: 29 jun y 12 oct. */
    internal fun trasladoLey19668(d: LocalDate): LocalDate = when (d.dayOfWeek) {
        DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY -> d.minusDays(d.dayOfWeek.value - 1L)
        DayOfWeek.FRIDAY -> d.plusDays(3)
        else -> d
    }

    /** Ley 20.299: 31 oct. Miércoles al viernes siguiente, martes al viernes anterior. */
    internal fun trasladoLey20299(d: LocalDate): LocalDate = when (d.dayOfWeek) {
        DayOfWeek.WEDNESDAY -> d.plusDays(2)
        DayOfWeek.TUESDAY -> d.minusDays(4)
        else -> d
    }

    /** Lista offline de feriados nacionales, ordenada por fecha. */
    fun forYear(year: Int): List<Holiday> {
        val easter = easterSunday(year)
        val list = listOf(
            Holiday(LocalDate.of(year, 1, 1), "Año Nuevo", true),
            Holiday(easter.minusDays(2), "Viernes Santo", false),
            Holiday(easter.minusDays(1), "Sábado Santo", false),
            Holiday(LocalDate.of(year, 5, 1), "Día del Trabajo", true),
            Holiday(LocalDate.of(year, 5, 21), "Día de las Glorias Navales", false),
            // Aproximado: solsticio de invierno (Ley 21.357)
            Holiday(LocalDate.of(year, 6, 21), "Día Nacional de los Pueblos Indígenas", false),
            Holiday(trasladoLey19668(LocalDate.of(year, 6, 29)), "San Pedro y San Pablo", false),
            Holiday(LocalDate.of(year, 7, 16), "Virgen del Carmen", false),
            Holiday(LocalDate.of(year, 8, 15), "Asunción de la Virgen", false),
            Holiday(LocalDate.of(year, 9, 18), "Fiestas Patrias", true),
            Holiday(LocalDate.of(year, 9, 19), "Día de las Glorias del Ejército", true),
            Holiday(trasladoLey19668(LocalDate.of(year, 10, 12)), "Día del Descubrimiento de Dos Mundos", false),
            Holiday(
                trasladoLey20299(LocalDate.of(year, 10, 31)),
                "Día Nacional de las Iglesias Evangélicas y Protestantes",
                false,
            ),
            Holiday(LocalDate.of(year, 11, 1), "Día de Todos los Santos", false),
            Holiday(LocalDate.of(year, 12, 8), "Inmaculada Concepción", false),
            Holiday(LocalDate.of(year, 12, 25), "Navidad", true),
        )
        return list.sortedBy { it.date }
    }
}
