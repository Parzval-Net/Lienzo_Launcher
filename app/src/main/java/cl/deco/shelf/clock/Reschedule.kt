package cl.deco.shelf.clock

/**
 * Eventos del sistema que dejan mal las alarmas absolutas (RTC) y el apagado nocturno. Lógica pura, testeable en JVM.
 * - BOOT / QUICKBOOT: AlarmManager pierde todas las alarmas al reiniciar.
 * - TIME_SET / TIMEZONE_CHANGED: las alarmas guardan una hora absoluta calculada con la hora y la zona anteriores.
 * - MY_PACKAGE_REPLACED: al actualizar el APK, AlarmManager borra las alarmas del paquete.
 * Todos son broadcasts protegidos del sistema: el receiver puede ser exported=false y los recibe igual.
 */
object Reschedule {
    const val BOOT = "android.intent.action.BOOT_COMPLETED"
    const val QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON"
    const val TIME_SET = "android.intent.action.TIME_SET"
    const val TIMEZONE = "android.intent.action.TIMEZONE_CHANGED"
    const val PACKAGE_REPLACED = "android.intent.action.MY_PACKAGE_REPLACED"

    val ACTIONS = setOf(BOOT, QUICKBOOT, TIME_SET, TIMEZONE, PACKAGE_REPLACED)

    fun handles(action: String?) = action != null && action in ACTIONS

    /** Reprograma todo y luego evalúa el apagado nocturno, solo si [action] es uno de [ACTIONS]. Devuelve true si actuó. */
    fun onEvent(action: String?, scheduleAll: () -> Unit, evaluateNight: () -> Unit): Boolean {
        if (!handles(action)) return false
        scheduleAll()
        evaluateNight()
        return true
    }
}
