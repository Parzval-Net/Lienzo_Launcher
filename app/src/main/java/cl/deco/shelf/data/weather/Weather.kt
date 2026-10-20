package cl.deco.shelf.data.weather

import java.time.LocalDate

/** [sunrise] y [sunset] en epoch ms (hora local de la ciudad convertida), null si la API no los trajo. */
data class DayForecast(
    val date: LocalDate, val tMin: Double, val tMax: Double, val code: Int, val precipProb: Int?,
    val sunrise: Long? = null, val sunset: Long? = null,
)

/** Estado día/noche según amanecer y atardecer de Open-Meteo. */
object SunDim {
    class State(val night: Boolean, val nextChange: Long?)

    /** Noche = el último evento solar ya ocurrido es un atardecer. Sin datos: null. */
    fun state(days: List<DayForecast>, now: Long): State? {
        val ev = ArrayList<Pair<Long, Boolean>>() // (instante, esAtardecer)
        for (d in days) { d.sunrise?.let { ev += it to false }; d.sunset?.let { ev += it to true } }
        if (ev.isEmpty()) return null
        ev.sortBy { it.first }
        val past = ev.lastOrNull { it.first <= now } ?: return State(night = !ev.first().second, nextChange = ev.first().first)
        val next = ev.firstOrNull { it.first > now }
        return State(night = past.second, nextChange = next?.first)
    }
}

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
