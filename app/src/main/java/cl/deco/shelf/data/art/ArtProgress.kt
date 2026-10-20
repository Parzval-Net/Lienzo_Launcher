package cl.deco.shelf.data.art

/** Texto del chip de descarga del home. Lógica pura (JVM). */
object ArtProgress {
    /**
     * "Obras 12/60" solo mientras la descarga está activa y falta alguna obra; null (chip oculto) al terminar, si no hay
     * catálogo o si no hay descarga en curso (sin red, pausada o con la colección completa).
     */
    fun chipText(done: Int, total: Int, downloading: Boolean, english: Boolean = false): String? =
        if (!downloading || total <= 0 || done >= total) null
        else "${if (english) "Artworks" else "Obras"} ${done.coerceAtLeast(0)}/$total"
}
