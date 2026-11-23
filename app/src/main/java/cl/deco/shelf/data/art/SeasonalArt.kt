package cl.deco.shelf.data.art

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import cl.deco.shelf.settings.ShelfPrefs
import cl.deco.shelf.util.Seasonal
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fondos de temporada: un respaldo liviano en el APK (`assets/seasonal/<temporada>/001.webp`) y el resto
 * descargado bajo demanda desde el repositorio solo durante la ventana de fechas (ver `seasonal/halloween/CREDITS.md`).
 * Al terminar la temporada se borran de disco. Bloqueante: llamar fuera del main thread.
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
        ensureFallback(season, list[0])
        if (list.any { !it.bundled && fileFor(season, it).length() == 0L }) {
            startDownload(season, list)
        }
        val start = (tickPrefs.getInt(KEY_LAST, -1) + 1).mod(list.size)
        var pickedIdx = -1
        var pickedFile: File? = null
        for (offset in list.indices) {
            val idx = (start + offset) % list.size
            val f = fileFor(season, list[idx])
            if (f.length() > 0L) {
                pickedIdx = idx
                pickedFile = f
                break
            }
        }
        if (pickedIdx < 0 || pickedFile == null) return null
        tickPrefs.edit().putInt(KEY_LAST, pickedIdx).apply()
        val w = list[pickedIdx]
        return Artwork(id = "season-$season-${w.file}", title = w.title, artist = w.author, year = null, museum = w.license, file = pickedFile)
    }

    private fun fileFor(season: String, w: Wallpaper) = File(dir, "$season-${w.file}")

    private fun ensureFallback(season: String, w: Wallpaper) {
        val f = fileFor(season, w)
        if (f.length() > 0L) return
        try {
            dir.mkdirs()
            val part = File(dir, f.name + ".part")
            app.assets.open("seasonal/$season/${w.file}").use { i -> part.outputStream().use { i.copyTo(it) } }
            part.renameTo(f)
        } catch (e: Exception) {
            Log.w(TAG, "no se pudo copiar ${w.file}: $e")
        }
    }

    private fun startDownload(season: String, list: List<Wallpaper>) {
        if (!networkAvailable()) return
        if (!downloading.compareAndSet(false, true)) return
        Thread({
            try {
                dir.mkdirs()
                for (w in list) {
                    if (w.bundled) continue
                    val dest = fileFor(season, w)
                    if (dest.length() > 0L) continue
                    if (!networkAvailable()) break
                    downloadOne(season, w.file, dest)
                }
            } finally {
                downloading.set(false)
            }
        }, "lienzo-seasonal-dl").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
    }

    private fun downloadOne(season: String, file: String, dest: File) {
        var conn: HttpURLConnection? = null
        val part = File(dir, dest.name + ".part")
        var ok = false
        try {
            conn = (URL(remoteUrl(season, file)).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6_000
                readTimeout = 10_000
                setRequestProperty("User-Agent", "Lienzo")
            }
            if (conn.responseCode !in 200..299) return
            conn.inputStream.use { ins ->
                part.outputStream().use { out ->
                    val buf = ByteArray(16 * 1024)
                    var total = 0
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > MAX_BYTES) return
                        out.write(buf, 0, n)
                    }
                }
            }
            ok = part.length() > 0L && part.renameTo(dest)
        } catch (e: Exception) {
            Log.w(TAG, "descarga $file: $e")
        } finally {
            conn?.disconnect()
            if (!ok) part.delete()
        }
    }

    private fun networkAvailable(): Boolean {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val net = cm.activeNetwork ?: return false
        return cm.getNetworkCapabilities(net)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    private class Wallpaper(val file: String, val title: String, val author: String, val license: String, val bundled: Boolean = false)

    companion object {
        private const val TAG = "SeasonalArt"
        private const val KEY_LAST = "last"
        private const val MAX_BYTES = 1024 * 1024
        private val downloading = AtomicBoolean(false)

        internal fun remoteUrl(season: String, file: String): String =
            "https://raw.githubusercontent.com/Parzval-Net/Lienzo_Launcher/main/seasonal/$season/$file"

        private val HALLOWEEN = listOf(
            Wallpaper("001.webp", "Casa embrujada", "Ilustración de CleanYourTV", "CC0 1.0", bundled = true),
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
