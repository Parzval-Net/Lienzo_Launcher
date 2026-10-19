package cl.deco.shelf.data.weather

import java.time.LocalDate

data class DayForecast(val date: LocalDate, val tMin: Double, val tMax: Double, val code: Int, val precipProb: Int?)

data class Weather(
    val currentTemp: Double,
    val currentCode: Int,
    val days: List<DayForecast>,
    val fetchedAt: Long,
    val isDay: Boolean = true,
    /** Probabilidad máxima de precipitación en las próximas 3 horas. */
    val rainSoonProb: Int? = null,
) {
    /**
     * Código a mostrar en la barra: el modelo puede marcar "despejado" ahora con lluvia probable
     * en las próximas horas. Con probabilidad >= 50% se muestra el código de precipitación del día.
     */
    val displayCode: Int
        get() {
            if (currentCode >= 51 || (rainSoonProb ?: 0) < RAIN_THRESHOLD) return currentCode
            val today = days.firstOrNull()?.code ?: 0
            return if (today >= 51) today else 61
        }

    private companion object {
        const val RAIN_THRESHOLD = 50
    }
}

/** Clima de Santiago. Bloqueante: llamar fuera del main thread. Devuelve cache si la red falla. */
interface WeatherSource {
    fun get(forceRefresh: Boolean = false): Weather?
}
