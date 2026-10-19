package cl.deco.shelf.data.holidays

import android.content.Context

/** Stub: lo implementa el agente Datos. */
class ChileHolidays(private val context: Context) : HolidaySource {
    override fun forYear(year: Int): List<Holiday> = emptyList()
}
