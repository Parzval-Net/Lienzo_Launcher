package cl.deco.shelf.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Servicio de accesibilidad que aplica la asignación de botones de [KeyMap] con cualquier control: abre apps, Entradas,
 * Ajustes o TV en vivo. Consume la pulsación completa (DOWN, repeticiones y UP) para que el sistema no ejecute además la
 * acción de fábrica; la acción corre al soltar. No escucha eventos de pantalla, solo teclas.
 *
 * Además, como sigue vivo con cualquier app en pantalla, vigila los HDMI: cuando un puerto pasa de vacío a cable puesto
 * pide a Lienzo el aviso "Un dispositivo se conectó en HDMI X" (ver [HdmiWatcher]).
 */
class KeyService : AccessibilityService() {

    private var hdmi: HdmiWatcher? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        hdmi = HdmiWatcher(this).also { it.start() }
        // Avisos del sistema al cambiar volumen o silencio (acciones ocultas de AudioManager, públicas en la práctica)
        val volFilter = android.content.IntentFilter().apply {
            addAction("android.media.VOLUME_CHANGED_ACTION")
            addAction("android.media.STREAM_MUTE_CHANGED_ACTION")
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(volumeReceiver, volFilter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(volumeReceiver, volFilter)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(volumeReceiver) }
        main.removeCallbacksAndMessages(null)
        hdmi?.stop(); hdmi = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // Android espera la respuesta de este método antes de entregar cada tecla a la app en pantalla: si el hilo principal
        // de Lienzo se atrasa, se nota en toda la navegación. Se registra cualquier atraso perceptible para poder medirlo.
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val lag = android.os.SystemClock.uptimeMillis() - event.eventTime
            if (lag > LAG_MS) Log.w(TAG, "tecla ${event.keyCode} llegó con $lag ms de atraso")
        }
        if (KeyMap.offerToLearner(event)) return true
        if (handleVolume(event)) return true
        if (KeyMap.reservedKey(event.keyCode)) return false
        // La acción se resuelve una vez por pulsación (al bajar): repeticiones y UP reusan el resultado sin volver a consultar
        // a PackageManager si la app del botón está instalada
        val code = event.keyCode
        val action = when {
            event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 -> KeyMap.actionFor(this, code).also { pressed[code] = it }
            pressed.containsKey(code) -> pressed[code]
            else -> KeyMap.actionFor(this, code)
        }
        if (event.action == KeyEvent.ACTION_UP) pressed.remove(code)
        if (action == null) {
            // Botones sin asignar: se registran para poder mapearlos (Ajustes > Control remoto > Asignar un botón)
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) Log.i(TAG, "sin asignar: key ${event.keyCode} scan ${event.scanCode}")
            return false
        }
        // Modo arte: mantener presionado abre el video apenas se cumple el tiempo (con las repeticiones del control) o al soltar
        val held = event.eventTime - event.downTime >= LONG_MS
        if (action == KeyMap.ART && event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount == 0) longFired = false
            else if (held && !longFired) { longFired = true; KeyMap.run(this, action, long = true) }
        }
        if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
            Log.i(TAG, "key ${event.keyCode} -> $action")
            if (action != KeyMap.ART) KeyMap.run(this, action)
            else if (!longFired) KeyMap.run(this, action, long = held)
            longFired = false
        }
        return true
    }

    /**
     * Volumen y silencio: la tecla se consume siempre. Dejarla pasar no sirve: con un filtro de teclas de accesibilidad activo,
     * la TCL ajusta el volumen antes del filtro y otra vez cuando la tecla vuelve de él (medido: 4 ajustes por pulsación con
     * el servicio, 2 sin él), así saltaba 2 o 3 pasos y Silenciar se anulaba solo. Como otros equipos podrían ajustar solo
     * después del filtro, si a los [VOL_CHECK_MS] el sistema no movió el volumen (ni el silencio) lo aplica el servicio.
     */
    private fun handleVolume(event: KeyEvent): Boolean {
        val dir = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> android.media.AudioManager.ADJUST_RAISE
            KeyEvent.KEYCODE_VOLUME_DOWN -> android.media.AudioManager.ADJUST_LOWER
            KeyEvent.KEYCODE_VOLUME_MUTE -> android.media.AudioManager.ADJUST_TOGGLE_MUTE
            else -> return false
        }
        if (event.action == KeyEvent.ACTION_DOWN && (event.repeatCount == 0 || dir != android.media.AudioManager.ADJUST_TOGGLE_MUTE)) {
            val pressedAt = event.eventTime
            main.postDelayed({
                if (lastVolumeChange >= pressedAt) return@postDelayed // el sistema ya lo aplicó (TCL)
                val am = getSystemService(AUDIO_SERVICE) as? android.media.AudioManager ?: return@postDelayed
                am.adjustSuggestedStreamVolume(dir, android.media.AudioManager.USE_DEFAULT_STREAM_TYPE, android.media.AudioManager.FLAG_SHOW_UI)
            }, VOL_CHECK_MS)
        }
        return true
    }

    private val main = Handler(Looper.getMainLooper())
    /** Último cambio de volumen o silencio informado por el sistema (uptime), para no aplicar dos veces la misma tecla. */
    @Volatile private var lastVolumeChange = 0L
    private val volumeReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { lastVolumeChange = android.os.SystemClock.uptimeMillis() }
    }

    private var longFired = false
    /** Acción resuelta al bajar cada tecla, hasta que se suelta. */
    private val pressed = HashMap<Int, String?>()

    private companion object { const val TAG = "KeyService"; const val LONG_MS = 600L; const val LAG_MS = 80L; const val VOL_CHECK_MS = 250L }
}

/**
 * Detecta un equipo enchufado en un HDMI con TvInputManager.TvInputCallback (API pública, sin permisos). En la TCL de
 * pruebas el estado pasa a INPUT_STATE_CONNECTED al enchufar y a CONNECTED_STANDBY al desenchufar (verificado con una
 * Switch). Solo HDMI (AV no tiene detección) y solo la transición vacío -> cable, confirmada 1,5 s después para ignorar
 * rebotes del conector.
 */
class HdmiWatcher(private val ctx: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val tim = ctx.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
    private val plugged = HashMap<String, Boolean>()

    private val callback = object : TvInputManager.TvInputCallback() {
        override fun onInputStateChanged(inputId: String, state: Int) {
            if (!isHdmiPort(inputId)) return
            val now = state == TvInputManager.INPUT_STATE_CONNECTED
            val before = plugged[inputId]
            plugged[inputId] = now
            if (now && before == false) main.postDelayed({ confirm(inputId) }, 1500)
        }
    }

    fun start() {
        val t = tim ?: return
        try {
            for (i in t.tvInputList) if (i.parentId == null && i.type == TvInputInfo.TYPE_HDMI) plugged[i.id] = t.getInputState(i.id) == TvInputManager.INPUT_STATE_CONNECTED
            t.registerCallback(callback, main)
        } catch (e: Exception) { Log.w("KeyService", "HDMI: $e") }
    }

    fun stop() { runCatching { tim?.unregisterCallback(callback) }; main.removeCallbacksAndMessages(null) }

    private fun isHdmiPort(id: String) = try {
        tim?.getTvInputInfo(id)?.let { it.type == TvInputInfo.TYPE_HDMI && it.parentId == null } ?: false
    } catch (_: Exception) { false }

    private fun confirm(inputId: String) {
        val still = try { tim?.getInputState(inputId) == TvInputManager.INPUT_STATE_CONNECTED } catch (_: Exception) { false }
        if (!still) return
        Log.i("KeyService", "HDMI conectado: $inputId")
        try {
            ctx.startActivity(Intent(KeyMap.ACTION_INPUT_CONNECTED).setClassName(ctx, "cl.deco.shelf.ui.MainActivity")
                .putExtra(KeyMap.EXTRA_INPUT_ID, inputId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        } catch (e: Exception) { Log.w("KeyService", "aviso HDMI: $e") }
    }
}
