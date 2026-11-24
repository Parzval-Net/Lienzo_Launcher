package cl.deco.shelf.service

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import cl.deco.shelf.settings.ShelfPrefs

/**
 * Auto-reparación del servicio de accesibilidad tras force-stop, killers OEM o actualizaciones:
 * si el usuario activó los botones y posee `WRITE_SECURE_SETTINGS`, restaura `KeyService` sin perder otros servicios.
 */
object ServiceRepair {
    private const val TAG = "ServiceRepair"
    private const val COOLDOWN_MS = 60_000L
    @Volatile private var lastAttemptMs = -COOLDOWN_MS

    fun repairIfNeeded(ctx: Context, ignoreCooldown: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!ignoreCooldown && now - lastAttemptMs < COOLDOWN_MS) return
        val prefs = ShelfPrefs(ctx)
        if (!prefs.keyServiceDesired) return
        if (ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") != PackageManager.PERMISSION_GRANTED) return
        val enabled = KeyMap.serviceEnabled(ctx)
        val running = KeyMap.serviceRunning(ctx)
        if (enabled && running) return
        lastAttemptMs = now
        try {
            val cr = ctx.contentResolver
            val me = ComponentName(ctx, KeyService::class.java).flattenToString()
            val cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            if (enabled && !running) {
                // Habilitado pero desconectado por el sistema: quitar y volver a agregar para re-enlazarlo
                val withoutMe = removeService(cur, ctx.packageName)
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, withoutMe)
                Thread.sleep(250)
            }
            val merged = mergeAccessibilityService(Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), me)
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, merged)
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            Log.i(TAG, "Servicio de botones reparado")
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo reparar KeyService: $e")
        }
    }

    internal fun removeService(existing: String?, pkg: String): String =
        existing.orEmpty().split(':')
            .filter { it.isNotBlank() && !it.startsWith("$pkg/") }
            .joinToString(":")

    internal fun mergeAccessibilityService(existing: String?, target: String): String {
        val list = existing.orEmpty().split(':')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toMutableList()
        if (list.none { it.equals(target, ignoreCase = true) }) list += target
        return list.distinctBy { it.lowercase() }.joinToString(":")
    }
}
