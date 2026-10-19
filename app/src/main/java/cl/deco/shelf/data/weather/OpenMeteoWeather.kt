package cl.deco.shelf.data.weather

import android.content.Context

/** Stub: lo implementa el agente Datos. */
class OpenMeteoWeather(private val context: Context) : WeatherSource {
    override fun get(forceRefresh: Boolean): Weather? = null
}
