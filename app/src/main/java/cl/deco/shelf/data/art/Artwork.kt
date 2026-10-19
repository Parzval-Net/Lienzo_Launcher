package cl.deco.shelf.data.art

import java.io.File

data class Artwork(
    val id: String,
    val title: String,
    val artist: String,
    val year: String?,
    val museum: String,
    val file: File,
)

/** Obras de dominio público cacheadas en disco. Bloqueante: llamar fuera del main thread. */
interface ArtSource {
    /** Siguiente obra disponible en cache, rotando. Null si la cache está vacía. */
    fun next(): Artwork?
    /** Descarga hasta [max] obras nuevas respetando el límite de cache. */
    fun prefetch(max: Int)
}
