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
    /**
     * Siguiente obra disponible, rotando. Null si no hay ninguna.
     * [forHome] true (fondo del launcher): solo obras a sangre; false (protector de pantalla): todas.
     */
    fun next(forHome: Boolean = true): Artwork?
    /** Si la colección curada no está completa en disco, dispara (o continúa) su descarga. [max] se ignora. */
    fun prefetch(max: Int)
    /** (obras listas, total) de la colección curada. (0, 0) si no aplica. */
    fun downloadProgress(): Pair<Int, Int> = 0 to 0
    /** true mientras el hilo de descarga de la colección curada está activo (incluye las esperas por 429/503). */
    fun downloading(): Boolean = false
    /** Inicia en background la descarga de la colección curada completa. No bloquea; idempotente. */
    fun startFullDownload() {}
}
