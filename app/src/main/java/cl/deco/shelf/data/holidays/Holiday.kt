package cl.deco.shelf.data.holidays

import java.time.LocalDate

data class Holiday(val date: LocalDate, val name: String, val irrenunciable: Boolean)

/** Feriados de Chile. Bloqueante: llamar fuera del main thread. */
interface HolidaySource {
    fun forYear(year: Int): List<Holiday>
}
