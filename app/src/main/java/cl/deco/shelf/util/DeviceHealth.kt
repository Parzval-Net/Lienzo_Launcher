package cl.deco.shelf.util

import android.os.SystemClock
import java.io.File

/**
 * Salud del deco: memoria de video contigua (CMA) libre y tiempo encendido.
 * En los Amlogic el video con DRM necesita CMA contigua; si se fragmenta, Netflix o Movistar fallan
 * al configurar el decodificador seguro (error -12) hasta reiniciar. Lectura instantánea, sin polling.
 */
object DeviceHealth {
    /** Bajo este umbral, medido en el home sin video, se recomienda reiniciar. */
    const val CMA_LOW_KB = 150L * 1024
    const val UPTIME_DAYS = 7

    data class Snapshot(val cmaFreeKb: Long, val cmaTotalKb: Long, val uptimeMs: Long) {
        val cmaLow get() = cmaTotalKb > 0 && cmaFreeKb in 0 until CMA_LOW_KB
        val longUptime get() = uptimeMs > UPTIME_DAYS * 86_400_000L
        val restartRecommended get() = cmaLow || longUptime
    }

    /** Acción sugerida en el panel: qué botón recibe el foco inicial. */
    enum class Action { NONE, FREE_MEMORY, RESTART }

    class Reco(val action: Action, val text: String)

    /**
     * Decisión de recomendación (JVM, sin Android).
     * - CMA baja y/o muchos días encendido juntos: reiniciar (liberar cachés no arregla la fragmentación acumulada).
     * - Solo CMA baja: primero liberar cachés de Lienzo (no interrumpe nada); si ya se liberó y sigue baja, reiniciar.
     * - Solo uptime alto: reiniciar.
     * - Todo sano pero RAM libre bajo [RAM_LOW_PCT]%: liberar cachés de Lienzo.
     */
    fun recommend(s: Snapshot, ramAvailKb: Long, ramTotalKb: Long, freed: Boolean = false): Reco = when {
        s.cmaLow && s.longUptime ->
            Reco(Action.RESTART, "Memoria de video baja y ${uptimeText(s.uptimeMs)} encendido. Reinicia el deco.")
        s.cmaLow && !freed ->
            Reco(Action.FREE_MEMORY, "Memoria de video baja. Libera cachés de Lienzo; si sigue baja, reinicia.")
        s.cmaLow ->
            Reco(Action.RESTART, "La memoria de video sigue baja. Reinicia el deco.")
        s.longUptime ->
            Reco(Action.RESTART, "El deco lleva ${uptimeText(s.uptimeMs)} encendido. Reinicia el deco.")
        ramTotalKb > 0 && ramAvailKb * 100 < ramTotalKb * RAM_LOW_PCT ->
            Reco(Action.FREE_MEMORY, "Poca RAM disponible. Libera cachés de Lienzo.")
        else -> Reco(Action.NONE, "Todo en orden.")
    }

    /** RAM disponible bajo este porcentaje del total sugiere liberar memoria. */
    const val RAM_LOW_PCT = 15

    /** "Recordarme más tarde": el aviso se oculta este tiempo. */
    const val SNOOZE_MS = 24L * 3_600_000L

    fun snoozed(nowMs: Long, untilMs: Long) = untilMs > nowMs && untilMs - nowMs <= SNOOZE_MS

    /** Tamaño de la lectura de RAM para el panel (ActivityManager.MemoryInfo), en KB. */
    class Ram(val availKb: Long, val totalKb: Long)

    fun readRam(ctx: android.content.Context): Ram {
        val am = ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return Ram(mi.availMem / 1024, mi.totalMem / 1024)
    }

    /** Mínimo de mejora (RAM o CMA) para considerar que "Liberar cachés de Lienzo" sirvió. */
    const val FREE_GAIN_KB = 20L * 1024

    /** Texto honesto del resultado de "Liberar cachés de Lienzo" según lo medido antes y después. */
    fun freeVerdict(ramBefore: Long, ramAfter: Long, cmaBefore: Long, cmaAfter: Long): String {
        val gain = maxOf(ramAfter - ramBefore, cmaAfter - cmaBefore)
        return if (gain >= FREE_GAIN_KB) "Se liberaron ${mbText(gain)} de cachés. Al volver al home las imágenes se cargan de nuevo y puede tardar un poco."
        else "Casi no cambió: Lienzo solo libera sus propias cachés y Android no le permite cerrar otras apps. Si la memoria de video sigue baja, reinicia."
    }

    fun mbText(kb: Long) = if (kb < 0) "n/d" else "${kb / 1024} MB"

    fun read(): Snapshot {
        var free = -1L; var total = -1L
        runCatching {
            File("/proc/meminfo").forEachLine { l ->
                when {
                    l.startsWith("CmaFree:") -> free = kb(l)
                    l.startsWith("CmaTotal:") -> total = kb(l)
                }
            }
        }
        return Snapshot(free, total, SystemClock.elapsedRealtime())
    }

    private fun kb(line: String) = line.filter { it.isDigit() }.toLongOrNull() ?: -1L

    fun uptimeText(ms: Long): String {
        val h = ms / 3_600_000L
        return if (h >= 24) "${h / 24} d ${h % 24} h" else "$h h ${(ms / 60_000L) % 60} min"
    }

    fun cmaText(s: Snapshot): String =
        if (s.cmaTotalKb <= 0) "No disponible" else "${s.cmaFreeKb / 1024} MB libres de ${s.cmaTotalKb / 1024} MB"
}
