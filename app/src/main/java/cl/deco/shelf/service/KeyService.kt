package cl.deco.shelf.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Servicio de accesibilidad que aplica la asignación de botones de [KeyMap] con cualquier control: abre apps, Entradas,
 * Ajustes o TV en vivo. Consume la pulsación completa (DOWN, repeticiones y UP) para que el sistema no ejecute además la
 * acción de fábrica; la acción corre al soltar. No escucha eventos de pantalla, solo teclas.
 */
class KeyService : AccessibilityService() {

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
