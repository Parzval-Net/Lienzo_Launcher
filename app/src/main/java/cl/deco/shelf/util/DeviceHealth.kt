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
