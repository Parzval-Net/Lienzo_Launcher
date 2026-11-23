package cl.deco.shelf.clock

import android.content.Context
import android.provider.Settings
import android.util.Log
import cl.deco.shelf.service.LocalAdb
import cl.deco.shelf.settings.ShelfPrefs

/**
 * Temporizador de apagado del televisor: al vencer envía KEYCODE_SLEEP (223) vía ADB local o,
 * como respaldo con WRITE_SECURE_SETTINGS, fija `attentive_timeout` en 30 s.
 */
object SleepTimer {
    private const val TAG = "ShelfSleep"
    private const val PREF_PREV_TIMEOUT = "sleep_prev_timeout"
    private const val PREF_PREV_SAVED = "sleep_prev_saved"
    private const val KEY_ATTENTIVE = "attentive_timeout"

    fun remainingMinutes(untilMs: Long, nowMs: Long): Int? {
        if (untilMs <= nowMs) return null
        return ((untilMs - nowMs + 59_999L) / 60_000L).toInt().coerceAtLeast(1)
    }

    fun formatChip(untilMs: Long, nowMs: Long, english: Boolean): String? {
        val m = remainingMinutes(untilMs, nowMs) ?: return null
        return if (english) "Sleep $m min" else "Apagar $m min"
    }

    fun set(ctx: Context, minutes: Int) {
        val prefs = ShelfPrefs(ctx)
        if (minutes <= 0) {
            prefs.sleepUntilMs = 0L
            ClockScheduler.cancelSleep(ctx)
            return
        }
        val until = System.currentTimeMillis() + minutes * 60_000L
        prefs.sleepUntilMs = until
        ClockScheduler.scheduleSleep(ctx, until)
    }

    /** Ejecuta el apagado en segundo plano cuando vence el temporizador. */
    fun execute(ctx: Context) {
        val prefs = ShelfPrefs(ctx)
        prefs.sleepUntilMs = 0L
        val adbOk = try {
            LocalAdb.connect(ctx, 2_000).use { it.shell("input keyevent 223") }
            true
        } catch (e: Exception) {
            Log.i(TAG, "ADB sleep no disponible ($e), probando attentive_timeout")
            false
        }
        if (adbOk) return
        if (!NightMode.hasPermission(ctx)) return
        try {
            val cr = ctx.contentResolver
            if (!prefs.sp.getBoolean(PREF_PREV_SAVED, false)) {
                val cur = Settings.Secure.getInt(cr, KEY_ATTENTIVE, -1)
                prefs.sp.edit().putInt(PREF_PREV_TIMEOUT, cur).putBoolean(PREF_PREV_SAVED, true).apply()
            }
            Settings.Secure.putInt(cr, KEY_ATTENTIVE, 30_000)
        } catch (e: Exception) {
            Log.w(TAG, "attentive_timeout fallback: $e")
        }
    }

    /** Restaura `attentive_timeout` si se usó el respaldo de apagado al volver al home o arrancar. */
    fun restoreTimeoutIfNeeded(ctx: Context) {
        val prefs = ShelfPrefs(ctx)
        if (!prefs.sp.getBoolean(PREF_PREV_SAVED, false)) return
        if (!NightMode.hasPermission(ctx)) return
        try {
            val prev = prefs.sp.getInt(PREF_PREV_TIMEOUT, -1)
            Settings.Secure.putInt(ctx.contentResolver, KEY_ATTENTIVE, prev)
            prefs.sp.edit().putBoolean(PREF_PREV_SAVED, false).apply()
        } catch (_: Exception) {}
    }
}
