package cl.deco.shelf.data.art

import android.content.Context

/** Stub: lo implementa el agente Datos. */
class MuseumArt(private val context: Context) : ArtSource {
    override fun next(): Artwork? = null
    override fun prefetch(max: Int) {}
}
