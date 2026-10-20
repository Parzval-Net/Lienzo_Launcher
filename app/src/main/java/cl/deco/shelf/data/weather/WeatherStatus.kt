package cl.deco.shelf.data.weather

/** Qué muestra el bloque del clima en la barra superior. */
enum class WeatherView { SHOW, LOADING, OFFLINE }

/** Decisión pura (JVM) del estado del clima según datos y conectividad. */
object WeatherStatus {
    /** Sin red, un dato más viejo que esto deja de mostrarse como vigente. */
    const val STALE_MS = 3L * 3_600_000L

    fun of(w: Weather?, online: Boolean, now: Long): WeatherView = when {
        w == null -> if (online) WeatherView.LOADING else WeatherView.OFFLINE
        !online && now - w.fetchedAt > STALE_MS -> WeatherView.OFFLINE
        else -> WeatherView.SHOW
    }
}
