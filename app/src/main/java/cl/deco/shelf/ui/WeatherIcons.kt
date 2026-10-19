package cl.deco.shelf.ui

import cl.deco.shelf.R

/** Código WMO (Open-Meteo) a ícono vectorial simple. */
object WeatherIcons {
    /** De noche, cielo despejado o poco nuboso se muestra luna. */
    fun iconFor(code: Int, isDay: Boolean = true): Int = if (!isDay && code in 0..2) R.drawable.ic_wx_moon else iconFor(code)

    fun iconFor(code: Int): Int = when (code) {
        0 -> R.drawable.ic_wx_sun
        1, 2 -> R.drawable.ic_wx_partly
        3 -> R.drawable.ic_wx_cloud
        45, 48 -> R.drawable.ic_wx_fog
        in 51..67, in 80..82 -> R.drawable.ic_wx_rain
        in 71..77, 85, 86 -> R.drawable.ic_wx_snow
        in 95..99 -> R.drawable.ic_wx_storm
        else -> R.drawable.ic_wx_cloud
    }
}
