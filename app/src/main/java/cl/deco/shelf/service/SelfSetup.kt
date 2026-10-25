package cl.deco.shelf.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import cl.deco.shelf.dream.ArtDreamService
import cl.deco.shelf.ui.MainActivity

/**
 * Configuración sin PC: lo que antes pedía `adb shell` desde un computador, hecho con [LocalAdb] contra el propio equipo.
 * Solo comandos fijos con componentes de Lienzo o del launcher original resuelto por PackageManager; nada viene de
 * afuera. Bloqueante: llamar fuera del hilo principal. Cada función devuelve un texto corto de resultado para Ajustes.
 */
object SelfSetup {

    /** Hace falta ADB para algo: permisos sin conceder o servicio de botones sin correr. */
    fun needed(ctx: Context): Boolean =
        !granted(ctx, "android.permission.WRITE_SECURE_SETTINGS") || !granted(ctx, "android.permission.READ_TV_LISTINGS") || !KeyMap.serviceRunning(ctx)

    private fun granted(ctx: Context, perm: String) = ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    /**
     * Permisos (Top Shelf, apagado nocturno, alarma sobre otras apps), arranque automático (TCL) y servicio de botones.
     * Si el servicio está habilitado pero detenido (TCL tras el appop) se quita y se vuelve a poner para que el sistema lo enlace.
     */
    fun permissionsAndKeys(ctx: Context, onApproval: () -> Unit): String = run(ctx, onApproval) { adb ->
        val pkg = ctx.packageName
        adb.shell("pm grant $pkg android.permission.READ_TV_LISTINGS")
        adb.shell("pm grant $pkg android.permission.WRITE_SECURE_SETTINGS")
        adb.shell("appops set $pkg SYSTEM_ALERT_WINDOW allow")
        adb.shell("cmd appops set $pkg APP_AUTO_START allow")
        val me = ComponentName(ctx, KeyService::class.java).flattenToString()
        val others = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            .split(':').filter { it.isNotBlank() && !it.startsWith("$pkg/") }
        if (KeyMap.serviceEnabled(ctx)) {
            adb.shell(if (others.isEmpty()) "settings delete secure enabled_accessibility_services"
                else "settings put secure enabled_accessibility_services ${quote(others.joinToString(":"))}")
            Thread.sleep(500)
        }
        adb.shell("settings put secure enabled_accessibility_services ${quote((others + me).joinToString(":"))}")
        adb.shell("settings put secure accessibility_enabled 1")
        "Permisos concedidos y botones activados"
    }

    /** Lienzo como pantalla de inicio y su protector como salvapantallas del sistema. */
    fun makeDefault(ctx: Context, onApproval: () -> Unit): String = run(ctx, onApproval) { adb ->
        adb.shell("cmd package set-home-activity --user 0 ${ComponentName(ctx, MainActivity::class.java).flattenToString()}")
        adb.shell("settings put secure screensaver_components ${ComponentName(ctx, ArtDreamService::class.java).flattenToString()}")
        adb.shell("settings put secure screensaver_enabled 1")
        "Lienzo es el inicio y el protector"
    }

    /** Devuelve el inicio al launcher del fabricante (o al primero que no sea Lienzo) y lo abre. */
    fun restoreStock(ctx: Context, onApproval: () -> Unit): String {
        val stock = stockHome(ctx) ?: return "No hay otro launcher instalado"
        return run(ctx, onApproval) { adb ->
            adb.shell("cmd package set-home-activity --user 0 ${stock.flattenToString()}")
            adb.shell("am start -a android.intent.action.MAIN -c android.intent.category.HOME")
            "Inicio devuelto a ${appLabel(ctx, stock.packageName)}"
        }
    }

    /** Launcher original: primero los conocidos de Google TV y Android TV, luego cualquier otro HOME que no sea Lienzo. */
    private fun stockHome(ctx: Context): ComponentName? {
        val pm = ctx.packageManager
        val homes = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_ALL)
            .map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
            .filter { it.packageName != ctx.packageName && !it.className.endsWith("FallbackHome") }
        return homes.firstOrNull { it.packageName in KNOWN_STOCK } ?: homes.firstOrNull()
    }

    private fun appLabel(ctx: Context, pkg: String) = try {
        ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }

    private fun quote(s: String) = "'" + s.replace("'", "") + "'"

    private inline fun run(ctx: Context, noinline onApproval: () -> Unit, block: (LocalAdb) -> String): String = try {
        LocalAdb.connect(ctx, APPROVAL_MS, onApproval).use { block(it) }
    } catch (e: Exception) {
        "No se pudo: ${e.message ?: e.javaClass.simpleName}"
    }

    private const val APPROVAL_MS = 60_000
    private val KNOWN_STOCK = setOf(
        "com.google.android.apps.tv.launcherx", "com.google.android.tvlauncher", "com.google.android.leanbacklauncher",
        "com.amazon.tv.launcher",
    )
}
