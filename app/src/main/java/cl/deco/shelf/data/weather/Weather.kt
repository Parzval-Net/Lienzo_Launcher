package cl.deco.shelf.data.weather

import java.time.LocalDate

data class DayForecast(val date: LocalDate, val tMin: Double, val tMax: Double, val code: Int, val precipProb: Int?)

data class Weather(val currentTemp: Double, val currentCode: Int, val days: List<DayForecast>, val fetchedAt: Long)

/** Clima de Santiago. Bloqueante: llamar fuera del main thread. Devuelve cache si la red falla. */
interface WeatherSource {
    fun get(forceRefresh: Boolean = false): Weather?
}
