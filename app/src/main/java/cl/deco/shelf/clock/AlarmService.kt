package cl.deco.shelf.clock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager

/**
 * Dispara alarmas y temporizadores: WakeLock que enciende la pantalla, sonido de alarma del sistema en loop a volumen
 * moderado (se corta solo a los 60 s) y tarjeta sobre cualquier app (TYPE_APPLICATION_OVERLAY). Sin permiso de overlay
 * abre AlarmActivity. Cualquier tecla detiene; OK largo pospone 5 min; un cambio de volumen también detiene.
 */
class AlarmService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var ringtone: Ringtone? = null
    private var wl: PowerManager.WakeLock? = null
    private var overlay: View? = null
    private var title = ""
    private var label = ""
    private var volReg = false
    private val volReceiver = object : BroadcastReceiver() { override fun onReceive(c: Context?, i: Intent?) { stopAll() } }
    private val autoStop = Runnable { stopAll() }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        when (i?.action) {
            ACTION_STOP -> stopAll()
            ACTION_SNOOZE -> { ClockScheduler.scheduleSnooze(this, label); stopAll() }
            else -> { title = i?.getStringExtra("title") ?: "Alarma"; label = i?.getStringExtra("label").orEmpty(); fire() }
        }
        return START_NOT_STICKY
    }

    private fun fire() {
        val canOverlay = Settings.canDrawOverlays(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Alarmas y temporizadores", NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) })
        val act = PendingIntent.getActivity(this, 1, Intent(this, AlarmActivity::class.java).putExtra("title", title).putExtra("label", label).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, CH).setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle(title)
            .setContentText(label.ifBlank { "Pulsa cualquier botón para detener" }).setCategory(Notification.CATEGORY_ALARM)
            .apply { if (!canOverlay) setFullScreenIntent(act, true) }.build()
        startForeground(NOTIF, n)
        ringing = true

        @Suppress("DEPRECATION")
        wl = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE, "shelf:alarm",
        ).apply { acquire(65_000L) }

        startSound()
        if (canOverlay) showOverlay() else try { startActivity(Intent(this, AlarmActivity::class.java).putExtra("title", title).putExtra("label", label).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { Log.w(TAG, "activity: $e") }
        if (!volReg) { registerReceiver(volReceiver, IntentFilter("android.media.VOLUME_CHANGED_ACTION")); volReg = true }
        main.removeCallbacks(autoStop)
        main.postDelayed(autoStop, 60_000L)
    }

    private fun startSound() {
        try {
            ringtone?.stop()
            val uri = RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ringtone = RingtoneManager.getRingtone(this, uri)?.apply {
                audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                isLooping = true
                volume = 0.6f
                play()
            }
            if (ringtone == null) Log.w(TAG, "sin tono de alarma ni de notificación")
        } catch (e: Exception) { Log.w(TAG, "sonido: $e") }
    }

    private fun showOverlay() {
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            overlay?.let { runCatching { wm.removeView(it) } }
            val card = AlarmCard(this, title, label, { startService(Intent(this, AlarmService::class.java).setAction(ACTION_STOP)) },
                { startService(Intent(this, AlarmService::class.java).setAction(ACTION_SNOOZE)) })
            val lp = WindowManager.LayoutParams(
                -1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
                PixelFormat.TRANSLUCENT,
            )
            wm.addView(card, lp) // sin FLAG_NOT_FOCUSABLE: recibe las teclas del control
            card.requestFocus()
            overlay = card
        } catch (e: Exception) { Log.w(TAG, "overlay: $e") }
    }

    private fun stopAll() {
        ringing = false
        main.removeCallbacks(autoStop)
        overlay?.let { runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } }
        overlay = null
        runCatching { ringtone?.stop() }
        ringtone = null
        runCatching { if (wl?.isHeld == true) wl?.release() }
        if (volReg) { runCatching { unregisterReceiver(volReceiver) }; volReg = false }
        sendBroadcast(Intent(ACTION_STOPPED).setPackage(packageName))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { stopAll(); super.onDestroy() }

    companion object {
        const val ACTION_STOP = "cl.deco.shelf.ALARM_STOP"
        const val ACTION_SNOOZE = "cl.deco.shelf.ALARM_SNOOZE"
        const val ACTION_STOPPED = "cl.deco.shelf.ALARM_STOPPED"
        @Volatile var ringing = false
        private const val CH = "alarm"
        private const val NOTIF = 77
        private const val TAG = "ShelfAlarm"

        fun fire(ctx: Context, title: String, label: String) {
            ctx.startForegroundService(Intent(ctx, AlarmService::class.java).putExtra("title", title).putExtra("label", label))
        }

        /** HOME (o cualquier salida de Shelf) también detiene. */
        fun stop(ctx: Context) { ctx.startService(Intent(ctx, AlarmService::class.java).setAction(ACTION_STOP)) }
    }
}
