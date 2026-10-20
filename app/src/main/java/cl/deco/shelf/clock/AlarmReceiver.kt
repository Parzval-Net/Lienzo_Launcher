package cl.deco.shelf.clock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Recibe los eventos de AlarmManager (no exportado). Sin UI propia: delega en AlarmService y NightMode. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val id = i.getIntExtra("id", 0)
        when (i.getStringExtra("kind")) {
            ClockScheduler.KIND_TIMER -> {
                val store = ClockStore(ctx)
                store.saveTimers(store.timers().filter { it.id != id })
                AlarmService.fire(ctx, "Temporizador", "")
            }
            ClockScheduler.KIND_ALARM -> {
                val store = ClockStore(ctx)
                val a = store.alarms().firstOrNull { it.id == id } ?: return
                if (a.days == 0) {
                    store.saveAlarms(store.alarms().map { if (it.id == id) it.copy(enabled = false) else it })
                    ClockScheduler.cancelAlarm(ctx, id) // un reprogramado concurrente (arranque del proceso) no debe dejar una alarma fantasma al día siguiente
                }
                else ClockScheduler.scheduleAlarm(ctx, a) // repetitiva: próximo día
                AlarmService.fire(ctx, "Alarma", a.label)
            }
            ClockScheduler.KIND_SNOOZE -> AlarmService.fire(ctx, "Alarma", i.getStringExtra("label").orEmpty())
            ClockScheduler.KIND_NIGHT -> NightMode.refresh(ctx) // evalúa y reprograma los dos eventos del día siguiente
        }
    }
}

/**
 * Reprograma alarmas, temporizadores y apagado nocturno ante reinicio, cambio de hora o de zona y actualización del APK
 * (ver [Reschedule]). No lanza UI. Es exported=false: todos son broadcasts protegidos del sistema.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        // La zona por defecto de la JVM se actualiza antes de entregar el broadcast; se refresca por si acaso
        if (i.action == Reschedule.TIMEZONE) java.util.TimeZone.setDefault(null)
        Reschedule.onEvent(i.action, { ClockScheduler.scheduleAll(ctx) }, { NightMode.evaluate(ctx) })
    }
}
