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
    }

    override fun onDestroy() {
        hdmi?.stop(); hdmi = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (KeyMap.offerToLearner(event)) return true
        if (KeyMap.reservedKey(event.keyCode)) return false
        val action = KeyMap.actionFor(this, event.keyCode)
        if (action == null) {
            // Botones sin asignar: se registran para poder mapearlos (Ajustes > Control remoto > Asignar un botón)
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) Log.i(TAG, "sin asignar: key ${event.keyCode} scan ${event.scanCode}")
            return false
        }
        if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
            Log.i(TAG, "key ${event.keyCode} -> $action")
            KeyMap.run(this, action)
        }
        return true
    }

    private companion object { const val TAG = "KeyService" }
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
