package cl.deco.shelf.guard

import android.content.Context
import java.io.File

/**
 * Registro de crashes en archivos planos (sin SharedPreferences ni Graph: debe funcionar en un proceso que se muere).
 * Regla: 3 crashes en 5 minutos activan el modo seguro; se limpia tras 10 minutos sin crashes o con el botón.
 * Idea del watchdog de Free My TV (MIT, https://github.com/simbasjungles/free-my-tv), reimplementada sin su código.
 */
object CrashLog {
    const val WINDOW_MS = 5 * 60_000L
    const val LIMIT = 3
    const val CALM_MS = 10 * 60_000L

    private fun log(dir: File) = File(dir, "crashes.log")
    private fun flag(dir: File) = File(dir, "safe.flag")

    /** Timestamps de crashes (ms), solo los de los últimos 15 minutos. */
    fun recent(ctx: Context, now: Long = System.currentTimeMillis()): List<Long> = recent(ctx.applicationContext.filesDir, now)

    fun recent(dir: File, now: Long): List<Long> = try {
        log(dir).readLines().mapNotNull { it.trim().toLongOrNull() }.filter { now - it < 3 * WINDOW_MS }
    } catch (_: Exception) { emptyList() }

    /** Lo llama el UncaughtExceptionHandler del proceso principal: anota el instante y, con 3 en 5 min, marca el modo seguro. */
    fun record(ctx: Context, now: Long = System.currentTimeMillis()) = record(ctx.applicationContext.filesDir, now)

    fun record(dir: File, now: Long) {
        try {
            val all = recent(dir, now) + now
            log(dir).writeText(all.joinToString("\n") + "\n")
            if (all.count { now - it < WINDOW_MS } >= LIMIT) flag(dir).writeText(now.toString())
        } catch (_: Exception) { }
    }

    /** true si hay que arrancar en modo seguro; limpia solo si pasaron 10 min sin crashes. */
    fun safeMode(ctx: Context, now: Long = System.currentTimeMillis()): Boolean = safeMode(ctx.applicationContext.filesDir, now)

    fun safeMode(dir: File, now: Long): Boolean {
        val f = flag(dir)
        if (!f.exists()) return false
        val last = recent(dir, now).maxOrNull() ?: (f.readText().trim().toLongOrNull() ?: 0L)
        if (now - last >= CALM_MS) { clear(dir); return false }
        return true
    }

    /**
     * Crashes que el UncaughtExceptionHandler no ve (abort nativo, p. ej. HWUI en la Mali-450, o ANR) quedan en el historial
     * de salidas de proceso de Android (API 30+). Se incorporan al registro una sola vez por salida.
     */
    fun absorbExits(ctx: Context) {
        try {
            val seenF = File(ctx.applicationContext.filesDir, "exits.seen")
            val seen = seenF.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: 0L
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            var newest = seen
            for (x in am.getHistoricalProcessExitReasons(ctx.packageName, 0, 6)) {
                if (x.timestamp <= seen) continue
                newest = maxOf(newest, x.timestamp)
                val bad = x.reason == android.app.ApplicationExitInfo.REASON_CRASH_NATIVE || x.reason == android.app.ApplicationExitInfo.REASON_ANR ||
                    x.reason == android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE
                // REASON_CRASH (java) ya lo anotó el handler; se evita duplicar si hay un registro a menos de 5 s
                val javaDup = x.reason == android.app.ApplicationExitInfo.REASON_CRASH && recent(ctx, x.timestamp + 10_000).any { Math.abs(it - x.timestamp) < 5_000 }
                if ((bad || x.reason == android.app.ApplicationExitInfo.REASON_CRASH) && !javaDup) record(ctx, x.timestamp)
            }
            if (newest != seen) seenF.writeText(newest.toString())
        } catch (_: Throwable) { }
    }

    fun clear(ctx: Context) = clear(ctx.applicationContext.filesDir)

    fun clear(dir: File) {
        runCatching { flag(dir).delete() }
        runCatching { log(dir).delete() }
    }
}
