package cl.deco.shelf.clock

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.KeyEvent

/** Respaldo cuando no hay permiso de overlay: tarjeta de alarma sobre la pantalla bloqueada o apagada. */
class AlarmActivity : Activity() {
    private val closer = object : BroadcastReceiver() { override fun onReceive(c: Context?, i: Intent?) { finish() } }
    private lateinit var card: AlarmCard

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        card = AlarmCard(this, intent.getStringExtra("title") ?: "Alarma", intent.getStringExtra("label").orEmpty(),
            { startService(Intent(this, AlarmService::class.java).setAction(AlarmService.ACTION_STOP)) },
            { startService(Intent(this, AlarmService::class.java).setAction(AlarmService.ACTION_SNOOZE)) })
        setContentView(card)
        registerReceiver(closer, IntentFilter(AlarmService.ACTION_STOPPED), RECEIVER_NOT_EXPORTED)
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean = if (::card.isInitialized) card.dispatchKeyEvent(e) else super.dispatchKeyEvent(e)

    override fun onDestroy() { runCatching { unregisterReceiver(closer) }; super.onDestroy() }
}
