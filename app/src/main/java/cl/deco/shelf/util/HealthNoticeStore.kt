package cl.deco.shelf.util

import android.content.SharedPreferences

/** Persistencia del estado de [HealthNotice] en las prefs "shelf" (reusa la clave de "Recordarme más tarde"). */
class HealthNoticeStore(private val sp: SharedPreferences) {
    fun load() = HealthNotice.State(
        lastEvalMs = sp.getLong("hn_eval", 0L), lowCount = sp.getInt("hn_low", 0), lastLowMs = sp.getLong("hn_low_ts", 0L),
        shownMs = sp.getLong("hn_shown", 0L), lastNoticeMs = sp.getLong("hn_notice", 0L),
        lastUptimeNoticeMs = sp.getLong("hn_up_notice", 0L), lastUptimeMs = sp.getLong("hn_uptime", 0L),
        snoozeUntilMs = sp.getLong("health_snooze_until", 0L),
    )

    fun save(s: HealthNotice.State) {
        sp.edit().putLong("hn_eval", s.lastEvalMs).putInt("hn_low", s.lowCount).putLong("hn_low_ts", s.lastLowMs)
            .putLong("hn_shown", s.shownMs).putLong("hn_notice", s.lastNoticeMs).putLong("hn_up_notice", s.lastUptimeNoticeMs)
            .putLong("hn_uptime", s.lastUptimeMs).putLong("health_snooze_until", s.snoozeUntilMs).apply()
    }

    /** "Último aviso" de Ajustes > Info: fecha del último aviso mostrado o "nunca". */
    fun lastNoticeText(english: Boolean = false): String {
        val ts = sp.getLong("hn_notice", 0L)
        if (ts <= 0L) return if (english) "never" else "nunca"
        return java.text.SimpleDateFormat("dd-MM-yyyy HH:mm", java.util.Locale.US).format(java.util.Date(ts))
    }
}
