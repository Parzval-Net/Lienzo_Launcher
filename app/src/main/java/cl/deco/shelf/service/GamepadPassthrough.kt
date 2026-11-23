package cl.deco.shelf.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import cl.deco.shelf.settings.ShelfPrefs

/**
 * Desactiva temporalmente el filtro de teclas del servicio de accesibilidad cuando hay un gamepad físico conectado
 * (para no interferir en juegos/emuladores) o durante unos segundos tras cambiar volumen (para equipos Android 14
 * donde filtrar teclas impide repetir volumen al mantener pulsado). Adaptado de CleanYourTV.
 */
class GamepadPassthrough(
    private val service: AccessibilityService,
    private val prefs: ShelfPrefs,
) : InputManager.InputDeviceListener {

    private val input = service.getSystemService(Context.INPUT_SERVICE) as InputManager
    private val handler = Handler(Looper.getMainLooper())
    private var volumePaused = false
    private var started = false
    private val resumeFromVolume = Runnable { volumePaused = false; refresh() }

    fun start() {
        if (started) return
        started = true
        input.registerInputDeviceListener(this, handler)
        refresh()
    }

    fun stop() {
        if (!started) return
        started = false
        input.unregisterInputDeviceListener(this)
        handler.removeCallbacks(resumeFromVolume)
        volumePaused = false
        setFilterEnabled(true)
    }

    fun onVolumeChanged() {
        if (!started || !prefs.volumePausesFilter) return
        volumePaused = true
        handler.removeCallbacks(resumeFromVolume)
        handler.postDelayed(resumeFromVolume, VOLUME_PAUSE_MS)
        refresh()
    }

    fun refresh() {
        if (!started) return
        val gamepadConnected = prefs.gamepadPassthrough && hasConnectedGamepad()
        val pauseForVol = prefs.volumePausesFilter && volumePaused
        setFilterEnabled(!(gamepadConnected || pauseForVol))
    }

    private fun hasConnectedGamepad(): Boolean = try {
        input.inputDeviceIds.any { id ->
            val dev = input.getInputDevice(id)
            dev != null && isGamepadSources(dev.sources)
        }
    } catch (_: Exception) { false }

    private fun setFilterEnabled(enabled: Boolean) {
        val info = service.serviceInfo ?: return
        val wantedFlags = if (enabled) {
            info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        } else {
            info.flags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS.inv()
        }
        if (info.flags != wantedFlags) {
            info.flags = wantedFlags
            service.serviceInfo = info
        }
    }

    override fun onInputDeviceAdded(deviceId: Int) = refresh()
    override fun onInputDeviceRemoved(deviceId: Int) = refresh()
    override fun onInputDeviceChanged(deviceId: Int) = refresh()

    companion object {
        private const val VOLUME_PAUSE_MS = 4000L
        private const val SOURCE_GAMEPAD = 0x00000401
        private const val SOURCE_JOYSTICK = 0x01000010

        /** Solo dispositivos con gamepad + joystick juntos (no controles remotos Bluetooth). */
        internal fun isGamepadSources(sources: Int): Boolean =
            (sources and SOURCE_GAMEPAD) == SOURCE_GAMEPAD && (sources and SOURCE_JOYSTICK) == SOURCE_JOYSTICK
    }
}
