package cl.deco.shelf.data.art

import android.content.Context
import android.util.Log
import cl.deco.shelf.settings.ShelfPrefs
import cl.deco.shelf.util.Seasonal
import java.io.File
import java.time.LocalDate

/**
 * Fondos de temporada que vienen en el APK (assets/seasonal/<temporada>), ya en 1920x1080 y compuestos para el home
 * (fotos del tema Halloween de CleanYourTV, ver assets/seasonal/halloween/CREDITS.md). Se copian a disco al usarse
 * y se borran al terminar la temporada. Bloqueante: llamar fuera del main thread.
 */
class SeasonalArt(context: Context) {

    private val app = context.applicationContext
    private val prefs by lazy { ShelfPrefs(app) }
    private val tickPrefs by lazy { app.getSharedPreferences("seasonal_art", Context.MODE_PRIVATE) }
    private val dir get() = File(app.filesDir, "seasonal")

    /** Próximo fondo de la temporada vigente, o null fuera de temporada (y entonces libera los archivos). */
    fun next(): Artwork? {
        val season = Seasonal.active(LocalDate.now(), prefs.seasonal)
        if (season == null) {
            if (dir.exists()) dir.deleteRecursively()
            return null
        }
        val list = HALLOWEEN
        val idx = (tickPrefs.getInt(KEY_LAST, -1) + 1).mod(list.size)
        tickPrefs.edit().putInt(KEY_LAST, idx).apply()
        val w = list[idx]
        val f = File(dir, "${season}-${w.file}")
        if (f.length() == 0L) try {
            dir.mkdirs()
            val part = File(dir, f.name + ".part")
            app.assets.open("seasonal/$season/${w.file}").use { i -> part.outputStream().use { i.copyTo(it) } }
            if (!part.renameTo(f)) return null
        } catch (e: Exception) {
            Log.w("SeasonalArt", "no se pudo copiar ${w.file}: $e")
            return null
        }
        return Artwork(id = "season-$season-${w.file}", title = w.title, artist = w.author, year = null, museum = w.license, file = f)
    }

    private class Wallpaper(val file: String, val title: String, val author: String, val license: String)

    companion object {
        private const val KEY_LAST = "last"
        private val HALLOWEEN = listOf(
            Wallpaper("001.jpg", "Casa embrujada", "Ilustración de CleanYourTV", "CC0 1.0"),
            Wallpaper("002.jpg", "Calabaza en la escalera", "Alabama Extension", "CC0 1.0"),
            Wallpaper("003.jpg", "Luna llena tras un bosque de secuoyas, California", "US Forest Service", "Dominio público"),
            Wallpaper("004.jpg", "Capilla del cementerio en la niebla, Chorzów", "Adrian Tync", "CC BY-SA 4.0"),
            Wallpaper("005.jpg", "Calabaza a la luz de una vela", "Douglaspperkins", "CC BY 4.0"),
            Wallpaper("006.jpg", "La luna saliendo tras los árboles", "Halleyuncometafotografo", "CC BY-SA 4.0"),
            Wallpaper("007.jpg", "Cementerio antiguo en la niebla", "Andrey Romanenko", "CC BY-SA 4.0"),
            Wallpaper("008.jpg", "Luna llena sobre el fiordo Gullmarn, Suecia", "W.carter", "CC BY-SA 4.0"),
        )
    }
}
