package cl.deco.shelf.shelf

import android.content.Context

/** Stub: lo implementa el agente Shelf+Dream. */
class TvProviderShelf(private val context: Context) : TopShelfProvider {
    override fun itemsFor(packageName: String): List<TopShelfItem> = emptyList()
}
