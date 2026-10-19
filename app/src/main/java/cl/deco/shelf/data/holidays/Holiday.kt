package cl.deco.shelf.data.holidays

import java.time.LocalDate

/** [counties]: códigos de región ISO (p. ej. CL-AP) si el feriado es regional; vacío = nacional. */
data class Holiday(val date: LocalDate, val name: String, val irrenunciable: Boolean, val counties: List<String> = emptyList())

/** Feriados del país y región elegidos en ajustes. Bloqueante: llamar fuera del main thread. */
interface HolidaySource {
    fun forYear(year: Int): List<Holiday>
}
