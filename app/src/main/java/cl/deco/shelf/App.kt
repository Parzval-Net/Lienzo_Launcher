package cl.deco.shelf

import android.app.Application
import android.content.Context
import cl.deco.shelf.data.art.ArtSource
import cl.deco.shelf.data.art.MuseumArt
import cl.deco.shelf.data.holidays.ChileHolidays
import cl.deco.shelf.data.holidays.HolidaySource
import cl.deco.shelf.data.weather.OpenMeteoWeather
import cl.deco.shelf.data.weather.WeatherSource
import cl.deco.shelf.shelf.TvProviderShelf

/** Application mínima: solo expone el grafo de dependencias, todo lazy. */
class App : Application() {
    val graph: Graph by lazy { Graph(this) }
}

/** Fuentes de datos instanciadas bajo demanda (nada se crea hasta que la UI lo pide). */
class Graph(ctx: Context) {
    private val app = ctx.applicationContext
    val weather: WeatherSource by lazy { OpenMeteoWeather(app) }
    val holidays: HolidaySource by lazy { ChileHolidays(app) }
    val art: ArtSource by lazy { MuseumArt(app) }
    val shelf: TvProviderShelf by lazy { TvProviderShelf(app) }

    companion object {
        @Volatile private var fallback: Graph? = null

        /** Usa el Graph de App si está registrada en el manifest; si no, uno compartido. */
        fun from(ctx: Context): Graph {
            (ctx.applicationContext as? App)?.let { return it.graph }
            return fallback ?: synchronized(this) {
                fallback ?: Graph(ctx).also { fallback = it }
            }
        }
    }
}
