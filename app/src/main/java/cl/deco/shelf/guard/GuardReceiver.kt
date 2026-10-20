package cl.deco.shelf.guard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Corre en el proceso aparte ":guard". Recibe el aviso del handler de crashes del proceso principal (que ya anotó el
 * timestamp en el archivo) y deja listo el modo seguro aunque el proceso principal no vuelva a arrancar bien.
 */
class GuardReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CRASHED) return
        val ts = intent.getLongExtra("ts", System.currentTimeMillis())
        // Idempotente: si el handler ya escribió este instante no se duplica
        if (CrashLog.recent(context).none { it == ts }) CrashLog.record(context, ts)
    }

    companion object { const val ACTION_CRASHED = "cl.deco.shelf.guard.CRASHED" }
}
