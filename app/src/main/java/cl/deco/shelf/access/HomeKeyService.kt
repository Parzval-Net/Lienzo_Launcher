package cl.deco.shelf.access

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import cl.deco.shelf.ui.MainActivity

/**
 * Opción "Botón Home con accesibilidad": solo filtra teclas (sin eventos de ventana, sin leer contenido). Si se pulsa
 * HOME y Lienzo no está en primer plano, abre MainActivity y consume la tecla. Sin estado ni Handlers: sirve en decos
 * donde no se puede cambiar el launcher por defecto. Se activa a mano en Accesibilidad.
 */
class HomeKeyService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* sin eventos */ }

    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_HOME || MainActivity.lienzoVisible) return false
        if (event.action == KeyEvent.ACTION_DOWN) {
            Log.i("LienzoHome", "HOME interceptado: abre Lienzo")
            startActivity(
                Intent(this, MainActivity::class.java)
                    .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
        return true // consume DOWN y UP
    }
}
