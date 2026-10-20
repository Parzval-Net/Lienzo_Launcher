package cl.deco.shelf.util

/**
 * Reglas antifatiga del aviso "Reiniciar recomendado" (lógica pura, JVM). Objetivo: que el chip no aparezca a cada rato.
 * 1. Solo se evalúa al volver al home, como máximo una vez cada [Timing.evalGapMs] (6 h).
 * 2. Una lectura baja de CMA no basta: hacen falta 2 evaluaciones consecutivas bajo el umbral, separadas por al menos
 *    [Timing.confirmGapMs] (30 min). Lienzo no puede ver el RSS de otras apps (Android 12), así que la separación de 6 h entre
 *    evaluaciones es lo que evita medir justo tras cerrar un video con las apps aún en caché.
 * 3. Mostrado, el chip queda hasta que se abre o se descarta. Descartado ("Recordarme más tarde") no vuelve en 24 h; ignorado
 *    se oculta solo a las [Timing.showMaxMs] (12 h) y tampoco vuelve en [Timing.cooldownMs] (24 h) desde que apareció.
 * 4. Si la condición se normaliza (CMA sobre el umbral + [HYSTERESIS_KB], o hubo reinicio) el chip se oculta y el contador parte de cero.
 * 5. Por 5 días encendido: como máximo un aviso por semana ([Timing.uptimeNoticeMs]).
 * 6. Nunca se evalúa ni se muestra con reproducción, modo arte o protector (lo decide quien llama con `blocked`).
 */
object HealthNotice {
    /** Duraciones. [FAST] (1/360) solo para builds con -PlienzoForceHealth, para poder probar las reglas en minutos. */
    class Timing(val evalGapMs: Long, val confirmGapMs: Long, val showMaxMs: Long, val cooldownMs: Long, val uptimeNoticeMs: Long) {
        companion object {
            private const val H = 3_600_000L
            val NORMAL = Timing(6 * H, 30 * 60_000L, 12 * H, 24 * H, 7 * 24 * H)
            val FAST = Timing(NORMAL.evalGapMs / 360, NORMAL.confirmGapMs / 360, NORMAL.showMaxMs / 360, NORMAL.cooldownMs / 360, NORMAL.uptimeNoticeMs / 360)
        }
    }

    /** Histéresis sobre [DeviceHealth.CMA_LOW_KB]: la condición se da por normalizada con CMA >= umbral + 30 MB. */
    const val HYSTERESIS_KB = 30L * 1024

    /** Estado persistido (ver HealthNoticeStore). 0 = nunca / sin dato. */
    data class State(
        val lastEvalMs: Long = 0L,        // última evaluación
        val lowCount: Int = 0,            // evaluaciones bajas consecutivas
        val lastLowMs: Long = 0L,         // instante de la última evaluación baja contada
        val shownMs: Long = 0L,           // desde cuándo está visible el chip (0 = oculto)
        val lastNoticeMs: Long = 0L,      // último aviso mostrado (para "Último aviso" y el enfriamiento de 24 h)
        val lastUptimeNoticeMs: Long = 0L, // último aviso por uptime (tope semanal)
        val lastUptimeMs: Long = 0L,      // uptime en la última revisión (detecta reinicios)
        val snoozeUntilMs: Long = 0L,     // "Recordarme más tarde"
    )

    fun rebooted(s: State, uptimeMs: Long) = uptimeMs < s.lastUptimeMs

    /** Tras un reinicio: se oculta el chip y el contador parte de cero; se conserva el historial de avisos. */
    fun afterReboot(s: State, uptimeMs: Long) = s.copy(lastEvalMs = 0L, lowCount = 0, lastLowMs = 0L, shownMs = 0L, lastUptimeMs = uptimeMs)

    fun canEvaluate(s: State, now: Long, t: Timing, blocked: Boolean) =
        !blocked && (s.lastEvalMs == 0L || now < s.lastEvalMs || now - s.lastEvalMs >= t.evalGapMs)

    /** Aplica una evaluación (ya confirmada) de CMA y uptime y devuelve el nuevo estado. */
    fun onEvaluation(s: State, now: Long, cmaFreeKb: Long, cmaTotalKb: Long, uptimeMs: Long, t: Timing): State {
        var n = s.copy(lastEvalMs = now, lastUptimeMs = uptimeMs)
        // El chip ignorado se oculta solo
        if (n.shownMs > 0 && now - n.shownMs >= t.showMaxMs) n = n.copy(shownMs = 0L)
        val known = cmaTotalKb > 0 && cmaFreeKb >= 0
        val low = known && cmaFreeKb < DeviceHealth.CMA_LOW_KB && uptimeMs >= DeviceHealth.CMA_MIN_UPTIME_H * 3_600_000L
        val normalized = !known || cmaFreeKb >= DeviceHealth.CMA_LOW_KB + HYSTERESIS_KB
        val longUptime = uptimeMs > DeviceHealth.UPTIME_DAYS * 86_400_000L
        if (normalized && !longUptime) n = n.copy(lowCount = 0, lastLowMs = 0L, shownMs = 0L)
        else if (low && (n.lastLowMs == 0L || now - n.lastLowMs >= t.confirmGapMs)) n = n.copy(lowCount = n.lowCount + 1, lastLowMs = now)
        val cmaTrigger = n.lowCount >= 2
        val upTrigger = longUptime && (n.lastUptimeNoticeMs == 0L || now - n.lastUptimeNoticeMs >= t.uptimeNoticeMs)
        val cooled = n.lastNoticeMs == 0L || now - n.lastNoticeMs >= t.cooldownMs
        if ((cmaTrigger || upTrigger) && n.shownMs == 0L && cooled && now >= n.snoozeUntilMs) {
            n = n.copy(shownMs = now, lastNoticeMs = now, lastUptimeNoticeMs = if (upTrigger) now else n.lastUptimeNoticeMs)
        }
        return n
    }

    fun visible(s: State, now: Long, t: Timing) = s.shownMs > 0 && now - s.shownMs < t.showMaxMs && now >= s.snoozeUntilMs

    /** El usuario abrió el panel: el aviso cumplió su función. */
    fun onOpened(s: State) = s.copy(shownMs = 0L)

    /** "Recordarme más tarde": oculto y sin volver antes de [snoozeMs]. */
    fun onDismissed(s: State, now: Long, snoozeMs: Long) = s.copy(shownMs = 0L, snoozeUntilMs = now + snoozeMs)
}
