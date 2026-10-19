package cl.deco.shelf.shelf

/** Contenido Top Shelf (preview channels y Watch Next) de una app. Bloqueante: llamar fuera del main thread. */
interface TopShelfProvider {
    fun itemsFor(packageName: String): List<TopShelfItem>
}

data class TopShelfItem(
    val title: String,
    val subtitle: String?,
    val imageUri: String?,
    val intentUri: String?,
)
