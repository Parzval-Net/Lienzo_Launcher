package cl.deco.shelf.dream

/**
 * Desplazamiento periódico del reloj del protector para evitar retención/quemado en pantallas OLED:
 * cambia unos dp cada minuto y alterna la esquina inferior cada 15 minutos.
 */
object DreamBurnIn {
    data class Position(val onRight: Boolean, val shiftXdp: Int, val shiftYdp: Int)

    private val OFFSETS_X = intArrayOf(0, 4, 8, 12, 9, 4, 0, -4, -9, -12, -8, -4, 2, 7, -5)
    private val OFFSETS_Y = intArrayOf(0, 3, 6, 4, 0, -4, -7, -5, -1, 3, 8, 5, -3, -8, 2)

    fun positionForMinute(minuteIndex: Long): Position {
        val m = minuteIndex.coerceAtLeast(0L)
        val onRight = ((m / 15L) % 2L) == 1L
        val step = (m % 15L).toInt()
        return Position(onRight = onRight, shiftXdp = OFFSETS_X[step], shiftYdp = OFFSETS_Y[step])
    }
}
