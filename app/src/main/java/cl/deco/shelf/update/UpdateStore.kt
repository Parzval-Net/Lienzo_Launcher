package cl.deco.shelf.update

import android.content.Context

/**
 * Estado del actualizador en sus propias prefs ("update"): no entra en el respaldo de configuración (que exporta "shelf")
 * y no mezcla etags ni cachés con los ajustes del usuario.
 */
class UpdateStore(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("update", Context.MODE_PRIVATE)

    fun load() = UpdateRules.State(
        lastCheckMs = sp.getLong("check", 0L), shownMs = sp.getLong("shown", 0L), lastNoticeMs = sp.getLong("notice", 0L),
        snoozeUntilMs = sp.getLong("snooze", 0L), availableCode = sp.getLong("avail", 0L),
    )

    fun save(s: UpdateRules.State) {
        sp.edit().putLong("check", s.lastCheckMs).putLong("shown", s.shownMs).putLong("notice", s.lastNoticeMs)
            .putLong("snooze", s.snoozeUntilMs).putLong("avail", s.availableCode).apply()
    }

    var etag: String? get() = sp.getString("etag", null); set(v) { sp.edit().apply { if (v == null) remove("etag") else putString("etag", v) }.apply() }

    /** Última versión publicada conocida (para el 304 y para abrir el panel sin volver a la red). */
    var info: UpdateInfo? get() = UpdateInfo.fromJson(sp.getString("info", null)); set(v) { sp.edit().apply { if (v == null) remove("info") else putString("info", v.toJson()) }.apply() }

    /** Texto de la última búsqueda para Ajustes > Info ("Al día", "Sin versiones publicadas", "Sin conexión"...). */
    var status: String get() = sp.getString("status", "") ?: ""; set(v) { sp.edit().putString("status", v).apply() }
}
