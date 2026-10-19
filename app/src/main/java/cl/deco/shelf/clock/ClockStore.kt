package cl.deco.shelf.clock

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Temporizador. [endAt] > 0 si corre; si está en pausa, [endAt] = 0 y queda [remainingMs]. */
data class TimerItem(val id: Int, val totalMs: Long, val endAt: Long, val remainingMs: Long) {
    val running get() = endAt > 0
    fun left(now: Long) = if (running) (endAt - now).coerceAtLeast(0) else remainingMs
}

/** Alarma. [days]: máscara bit0 = lunes ... bit6 = domingo; 0 = única. */
data class AlarmItem(val id: Int, val hour: Int, val minute: Int, val days: Int, val label: String, val enabled: Boolean)

/** Temporizadores y alarmas en SharedPreferences "shelf_clock" (JSON). Máx. 3 temporizadores. */
class ClockStore(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("shelf_clock", Context.MODE_PRIVATE)

    fun timers(): List<TimerItem> = try {
        val a = JSONArray(sp.getString(TIMERS, "[]"))
        (0 until a.length()).map { val o = a.getJSONObject(it); TimerItem(o.getInt("id"), o.getLong("total"), o.getLong("end"), o.getLong("rem")) }
    } catch (_: Exception) { emptyList() }

    fun saveTimers(l: List<TimerItem>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("id", it.id).put("total", it.totalMs).put("end", it.endAt).put("rem", it.remainingMs)) }
        sp.edit().putString(TIMERS, a.toString()).apply()
    }

    fun alarms(): List<AlarmItem> = try {
        val a = JSONArray(sp.getString(ALARMS, "[]"))
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            AlarmItem(o.getInt("id"), o.getInt("h"), o.getInt("m"), o.getInt("d"), o.optString("l"), o.getBoolean("on"))
        }
    } catch (_: Exception) { emptyList() }

    fun saveAlarms(l: List<AlarmItem>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("id", it.id).put("h", it.hour).put("m", it.minute).put("d", it.days).put("l", it.label).put("on", it.enabled)) }
        sp.edit().putString(ALARMS, a.toString()).apply()
    }

    fun nextId(): Int { val n = sp.getInt(NEXT, 1); sp.edit().putInt(NEXT, n + 1).apply(); return n }

    /** Agrega un temporizador corriendo. Devuelve null si ya hay 3. */
    fun startTimer(ms: Long): TimerItem? {
        val l = timers()
        if (l.size >= MAX_TIMERS) return null
        val t = TimerItem(nextId(), ms, System.currentTimeMillis() + ms, ms)
        saveTimers(l + t)
        return t
    }

    companion object {
        const val MAX_TIMERS = 3
        private const val TIMERS = "timers"; private const val ALARMS = "alarms"; private const val NEXT = "next_id"
    }
}
