package cl.deco.shelf.data.art

import android.content.Context
import cl.deco.shelf.settings.ShelfPrefs

/**
 * Combina la colección curada y "Mis fondos" según el ajuste "Fuente del fondo" (ver [SourcePolicy]).
 * Respeta home=false: en el fondo del launcher solo entran obras a sangre, en el protector todas.
 * Si "Mis fondos" está vacío cae a la colección curada. La descarga y su progreso son siempre de la colección curada.
 */
class CompositeArt(context: Context, private val curated: ArtSource, private val mine: UserArt) : ArtSource {

    private val app = context.applicationContext
    private val prefs by lazy { ShelfPrefs(app) }
    private val tickPrefs by lazy { app.getSharedPreferences("composite_art", Context.MODE_PRIVATE) }

    override fun next(forHome: Boolean): Artwork? {
        val mode = prefs.bgSource
        if (mode == SourcePolicy.CURATED) return curated.next(forHome)
        val key = if (forHome) "tick_home" else "tick_all"
        val tick = tickPrefs.getInt(key, 0)
        tickPrefs.edit().putInt(key, tick + 1).apply()
        for (s in SourcePolicy.order(mode, tick, mine.count(forHome) > 0)) {
            val a = when (s) { Src.CURATED -> curated.next(forHome); Src.MINE -> mine.next(forHome) }
            if (a != null) return a
        }
        return null
    }

    override fun prefetch(max: Int) = curated.prefetch(max)
    override fun downloadProgress() = curated.downloadProgress()
    override fun startFullDownload() = curated.startFullDownload()
}
