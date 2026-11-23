package cl.deco.shelf.ui

import android.content.Context
import cl.deco.shelf.clock.ClockScheduler
import cl.deco.shelf.clock.NightMode
import cl.deco.shelf.settings.ShelfPrefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exportación y restauración de preferencias en JSON, excluyendo estado transitorio o propio del hardware.
 */
object BackupManager {

    private val RUNTIME_EXACT = setOf(
        "night_active",
        "night_prev",
        "health_snooze_until",
        "perm_asked",
        "sleep_until_ms",
        "sleep_prev_timeout",
        "sleep_prev_saved",
        "boot_pending_ms",
        "disabled_by_lienzo",
        "key_service_desired",
        "last_input_id",
    )

    fun isRuntimeKey(k: String): Boolean =
        k.startsWith("hn_") || k.startsWith("last_art_") || k.startsWith("space_") || k in RUNTIME_EXACT

    fun serializePrefs(pkg: String, all: Map<String, *>): String {
        val prefsObj = JSONObject()
        for ((k, v) in all.toSortedMap()) {
            if (isRuntimeKey(k)) continue
            val o = JSONObject()
            when (v) {
                is String -> o.put("t", "s").put("v", v)
                is Int -> o.put("t", "i").put("v", v)
                is Long -> o.put("t", "l").put("v", v)
                is Boolean -> o.put("t", "b").put("v", v)
                is Float -> o.put("t", "f").put("v", v.toDouble())
                is Set<*> -> o.put("t", "ss").put("v", JSONArray(v.map { it.toString() }.sorted()))
                else -> continue
            }
            prefsObj.put(k, o)
        }
        return JSONObject().put("app", pkg).put("format", 1).put("prefs", prefsObj).toString(2)
    }

    fun parseBackupPrefs(jsonText: String): Map<String, Any> {
        val root = JSONObject(jsonText)
        val all = root.getJSONObject("prefs")
        val out = LinkedHashMap<String, Any>()
        for (k in all.keys()) {
            if (isRuntimeKey(k)) continue
            val o = all.getJSONObject(k)
            when (o.getString("t")) {
                "s" -> out[k] = o.getString("v")
                "i" -> out[k] = o.getInt("v")
                "l" -> out[k] = o.getLong("v")
                "b" -> out[k] = o.getBoolean("v")
                "f" -> out[k] = o.getDouble("v").toFloat()
                "ss" -> {
                    val a = o.getJSONArray("v")
                    out[k] = (0 until a.length()).map { a.getString(it) }.toSet()
                }
            }
        }
        return out
    }

    fun exportJson(ctx: Context): String = serializePrefs(ctx.packageName, ShelfPrefs(ctx).sp.all)

    fun exportToFile(ctx: Context): String {
        val dir = ctx.getExternalFilesDir("backup") ?: return t("Sin almacenamiento disponible", "No storage available")
        dir.mkdirs()
        val f = File(dir, "shelf-backup-" + SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()) + ".json")
        return try {
            f.writeText(exportJson(ctx))
            t(
                "Guardado: /sdcard/Android/data/${ctx.packageName}/files/backup/${f.name} (respaldar con adb pull)",
                "Saved: /sdcard/Android/data/${ctx.packageName}/files/backup/${f.name} (adb pull)",
            )
        } catch (e: Exception) {
            t("Error al exportar: ${e.message}", "Export failed: ${e.message}")
        }
    }

    fun listBackups(ctx: Context): List<File> =
        ctx.getExternalFilesDir("backup")?.listFiles { f -> f.name.startsWith("shelf-backup-") && f.extension == "json" }
            ?.sortedByDescending { it.name }.orEmpty()

    fun restoreFromJson(ctx: Context, jsonText: String) {
        val parsed = parseBackupPrefs(jsonText)
        val sp = ShelfPrefs(ctx).sp
        val ed = sp.edit()
        for (k in sp.all.keys) if (!isRuntimeKey(k)) ed.remove(k)
        for ((k, v) in parsed) {
            when (v) {
                is String -> ed.putString(k, v)
                is Int -> ed.putInt(k, v)
                is Long -> ed.putLong(k, v)
                is Boolean -> ed.putBoolean(k, v)
                is Float -> ed.putFloat(k, v)
                is Set<*> -> @Suppress("UNCHECKED_CAST") ed.putStringSet(k, v as Set<String>)
            }
        }
        ed.commit()
        val app = ctx.applicationContext
        Thread({ NightMode.refresh(app); ClockScheduler.rescheduleAll(app) }, "restore-resched").start()
    }

    fun restoreFromFile(ctx: Context, f: File) = restoreFromJson(ctx, f.readText())
}
