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
                if (a.days == 0) store.saveAlarms(store.alarms().map { if (it.id == id) it.copy(enabled = false) else it })
                else ClockScheduler.scheduleAlarm(ctx, a) // repetitiva: próximo día
                AlarmService.fire(ctx, "Alarma", a.label)
            }
            ClockScheduler.KIND_SNOOZE -> AlarmService.fire(ctx, "Alarma", i.getStringExtra("label").orEmpty())
            ClockScheduler.KIND_NIGHT -> NightMode.refresh(ctx) // evalúa y reprograma los dos eventos del día siguiente
        }
    }
}

/** BOOT_COMPLETED: solo reprograma alarmas, temporizadores y apagado nocturno. No lanza UI. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED || i.action == "android.intent.action.QUICKBOOT_POWERON") {
            ClockScheduler.scheduleAll(ctx)
            NightMode.evaluate(ctx)
        }
    }
}
