package cl.deco.shelf.clock

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.Calendar

/** Programa con AlarmManager exacto los temporizadores, las alarmas y los dos eventos diarios del apagado nocturno. */
object ClockScheduler {
    const val KIND_TIMER = "timer"
    const val KIND_ALARM = "alarm"
    const val KIND_SNOOZE = "snooze"
    const val KIND_NIGHT = "night"
    private const val ACTION = "cl.deco.shelf.CLOCK_EVENT"
    private const val TAG = "ShelfClock"

    private fun pi(ctx: Context, code: Int, kind: String, id: Int, label: String = ""): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, code,
            Intent(ACTION).setComponent(ComponentName(ctx, AlarmReceiver::class.java)).putExtra("kind", kind).putExtra("id", id).putExtra("label", label),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun set(ctx: Context, at: Long, p: PendingIntent) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try {
            val canExact = android.os.Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
            if (canExact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p) // sin permiso exacto: aproximado
        } catch (e: SecurityException) { Log.w(TAG, "alarma: $e") }
    }

    private fun cancel(ctx: Context, p: PendingIntent) { (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(p) }

    fun scheduleTimer(ctx: Context, t: TimerItem) { if (t.running) set(ctx, t.endAt, pi(ctx, 1000 + t.id, KIND_TIMER, t.id)) }
    fun cancelTimer(ctx: Context, id: Int) = cancel(ctx, pi(ctx, 1000 + id, KIND_TIMER, id))
    fun scheduleSnooze(ctx: Context, label: String) = set(ctx, System.currentTimeMillis() + 5 * 60_000L, pi(ctx, 9000, KIND_SNOOZE, 0, label))

    fun scheduleAlarm(ctx: Context, a: AlarmItem) {
        val p = pi(ctx, 2000 + a.id, KIND_ALARM, a.id, a.label)
        if (!a.enabled) { cancel(ctx, p); return }
        nextTime(a, System.currentTimeMillis())?.let { set(ctx, it, p) }
    }

    fun cancelAlarm(ctx: Context, id: Int) = cancel(ctx, pi(ctx, 2000 + id, KIND_ALARM, id))

    /** Próximo disparo de la alarma tras [now], o null. */
    fun nextTime(a: AlarmItem, now: Long): Long? {
        for (d in 0..7) {
            val c = Calendar.getInstance().apply {
                timeInMillis = now; add(Calendar.DAY_OF_YEAR, d)
                set(Calendar.HOUR_OF_DAY, a.hour); set(Calendar.MINUTE, a.minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            if (c.timeInMillis <= now) continue
            val bit = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7 // lunes = 0
            if (a.days == 0 || a.days and (1 shl bit) != 0) return c.timeInMillis
        }
        return null
    }

    /** Reprograma todo lo persistido y evalúa el apagado nocturno: arranque del proceso, eventos de [Reschedule] y cambios de ajustes. */
    fun rescheduleAll(ctx: Context) { scheduleAll(ctx); NightMode.evaluate(ctx) }

    /** Reprograma todo lo persistido (temporizadores, alarmas y los dos eventos del apagado nocturno). */
    fun scheduleAll(ctx: Context) {
        val store = ClockStore(ctx)
        val now = System.currentTimeMillis()
        store.timers().forEach { if (it.running && it.endAt > now) scheduleTimer(ctx, it) }
        store.alarms().forEach { scheduleAlarm(ctx, it) }
        NightMode.scheduleEvents(ctx)
    }

    /** Evento diario del apagado nocturno: [code] 3001 = desde, 3002 = hasta. */
    fun scheduleNightAt(ctx: Context, code: Int, minuteOfDay: Int) {
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, minuteOfDay / 60); set(Calendar.MINUTE, minuteOfDay % 60); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        set(ctx, c.timeInMillis, pi(ctx, code, KIND_NIGHT, code))
    }

    fun cancelNight(ctx: Context) { cancel(ctx, pi(ctx, 3001, KIND_NIGHT, 3001)); cancel(ctx, pi(ctx, 3002, KIND_NIGHT, 3002)) }
}
