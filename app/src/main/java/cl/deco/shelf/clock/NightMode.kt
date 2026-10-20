package cl.deco.shelf.clock

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import cl.deco.shelf.settings.ShelfPrefs
import java.util.Calendar

/**
 * Apagado nocturno: dentro de la ventana [desde, hasta) fija Settings.Secure "attentive_timeout" (apagado por
 * inactividad, aplica aunque haya video con wakelock) y fuera de ella restaura el valor previo (hoy -1).
 * Requiere WRITE_SECURE_SETTINGS (pm grant). Maneja ventanas que cruzan la medianoche.
 */
object NightMode {
    private const val TAG = "ShelfNight"
    private const val KEY = "attentive_timeout"
    const val PERM = "android.permission.WRITE_SECURE_SETTINGS"

    fun hasPermission(ctx: Context) = ctx.checkSelfPermission(PERM) == PackageManager.PERMISSION_GRANTED

    fun inWindow(nowMin: Int, from: Int, to: Int) = when {
        from == to -> false
        from < to -> nowMin in from until to
        else -> nowMin >= from || nowMin < to
    }

    fun active(ctx: Context) = active(ShelfPrefs(ctx))
    fun active(p: ShelfPrefs) = p.sp.getBoolean("night_active", false)

    /** Aplica el estado correcto para la hora actual. Devuelve true si la ventana está activa. */
    fun evaluate(ctx: Context): Boolean {
        val p = ShelfPrefs(ctx)
        val c = Calendar.getInstance()
        val now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        val want = p.nightOn && inWindow(now, p.nightFromMin, p.nightToMin)
        if (!hasPermission(ctx)) return false
        val cr = ctx.contentResolver
        try {
            val activeNow = p.sp.getBoolean("night_active", false)
            if (want) {
                if (!activeNow) p.sp.edit().putInt("night_prev", Settings.Secure.getInt(cr, KEY, -1)).putBoolean("night_active", true).apply()
                val v = p.nightIdleMin * 60_000
                if (Settings.Secure.getInt(cr, KEY, -1) != v) Settings.Secure.putInt(cr, KEY, v)
            } else if (activeNow) {
                Settings.Secure.putInt(cr, KEY, p.sp.getInt("night_prev", -1))
                p.sp.edit().putBoolean("night_active", false).apply()
            }
        } catch (e: Exception) { Log.w(TAG, "attentive_timeout: $e") }
        return p.sp.getBoolean("night_active", false)
    }

    /** Dos eventos diarios (desde y hasta); sin la función activa se cancelan. */
    fun scheduleEvents(ctx: Context) {
        val p = ShelfPrefs(ctx)
        if (!p.nightOn) { ClockScheduler.cancelNight(ctx); return }
        ClockScheduler.scheduleNightAt(ctx, 3001, p.nightFromMin)
        ClockScheduler.scheduleNightAt(ctx, 3002, p.nightToMin)
    }

    /** Cambió el ajuste o arrancó la app: evalúa ahora y reprograma. */
    fun refresh(ctx: Context): Boolean { scheduleEvents(ctx); return evaluate(ctx) }
}
