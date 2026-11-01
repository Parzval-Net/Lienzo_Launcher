package cl.deco.shelf.util

import java.time.LocalDate

/**
 * Skin de temporada. Solo cambia colores y textos de la barra superior dentro de su ventana de fechas;
 * fuera de ella no queda nada aplicado y Lienzo vuelve solo a su aspecto normal.
 */
object Seasonal {
    /** Naranjo calabaza, violeta y fondo casi negro (misma base que el tema Halloween de CleanYourTV). */
    val HALLOWEEN = Palette(0xFFFF7A1A.toInt(), 0xFFB48CFF.toInt(), 0xFF07050B.toInt())

    /** Halloween: del 24 de octubre al 1 de noviembre inclusive. */
    fun halloween(d: LocalDate): Boolean =
        (d.monthValue == 10 && d.dayOfMonth >= 24) || (d.monthValue == 11 && d.dayOfMonth == 1)

    const val HALLOWEEN_ID = "halloween"

    /** Temporada vigente para [d] ("halloween"), o null fuera de fechas o con el ajuste apagado. */
    fun active(d: LocalDate, enabled: Boolean): String? = if (enabled && halloween(d)) HALLOWEEN_ID else null

    /** Paleta vigente para [d], o null fuera de temporada o con el ajuste apagado. */
    fun paletteFor(d: LocalDate, enabled: Boolean): Palette? = if (enabled && halloween(d)) HALLOWEEN else null

    /** Texto de la línea de feriado cuando no hay feriado ese día. */
    fun label(d: LocalDate): String? = if (d.monthValue == 10 && d.dayOfMonth == 31) "🎃 Feliz Halloween" else "🎃 Temporada de Halloween"

    /** Saludo de noche en la noche de Halloween. */
    fun greeting(d: LocalDate, hour: Int): String? = if (d.monthValue == 10 && d.dayOfMonth == 31 && (hour >= 19 || hour < 6)) "Noche de Halloween" else null
}
