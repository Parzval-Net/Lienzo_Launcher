package cl.deco.shelf.service

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.tv.TvInputManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import cl.deco.shelf.dream.ArtDreamService
import cl.deco.shelf.settings.ShelfPrefs
import cl.deco.shelf.ui.MainActivity
import cl.deco.shelf.util.MemoryCleaner

/**
 * Configuración sin PC: lo que antes pedía `adb shell` desde un computador, hecho con [LocalAdb] contra el propio equipo.
 * Solo comandos fijos o reglas conservadoras en [DebloatRules]. Bloqueante: llamar fuera del hilo principal.
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
        adb.shell("appops set $pkg REQUEST_INSTALL_PACKAGES allow")
        adb.shell("cmd appops set $pkg REQUEST_INSTALL_PACKAGES allow")
        adb.shell("cmd appops set $pkg APP_AUTO_START allow")
        ShelfPrefs(ctx).keyServiceDesired = true
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
        val me = ComponentName(ctx, MainActivity::class.java)
        val prefs = ShelfPrefs(ctx)
        adb.shell("cmd package set-home-activity --user 0 ${me.flattenToString()}")
        adb.shell("settings put secure screensaver_components ${ComponentName(ctx, ArtDreamService::class.java).flattenToString()}")
        adb.shell("settings put secure screensaver_enabled 1")
        // Android 9 (Mi Box S y otros): el launcher de sistema tiene prioridad y gana igual; se deshabilita (reversible)
        val home = ctx.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
        val winner = home?.activityInfo?.packageName
        if (winner != null && winner != ctx.packageName && winner in DebloatRules.KNOWN_STOCK_LAUNCHERS) {
            adb.shell("pm disable-user --user 0 $winner")
            adb.shell("am force-stop $winner")
            adb.shell("cmd package set-home-activity --user 0 ${me.flattenToString()}")
            prefs.disabledByLienzo = prefs.disabledByLienzo + winner
            "Lienzo es el inicio y el protector (launcher original deshabilitado)"
        } else "Lienzo es el inicio y el protector"
    }

    /** Deshabilita launchers de fábrica instalados para liberar RAM (150-250 MB en Google TV), 100% reversible. */
    fun disableStockLauncher(ctx: Context, onApproval: () -> Unit): String = run(ctx, onApproval) { adb ->
        val me = ComponentName(ctx, MainActivity::class.java)
        adb.shell("cmd package set-home-activity --user 0 ${me.flattenToString()}")
        val prefs = ShelfPrefs(ctx)
        val enabled = parsePackageList(adb.shell("pm list packages -e"))
        val targets = enabled.filter { it in DebloatRules.KNOWN_STOCK_LAUNCHERS }
        if (targets.isEmpty()) return@run "El launcher original ya estaba deshabilitado"
        val disabled = HashSet(prefs.disabledByLienzo)
        for (p in targets) {
            adb.shell("pm disable-user --user 0 $p")
            adb.shell("am force-stop $p")
            disabled += p
        }
        prefs.disabledByLienzo = disabled
        adb.shell("cmd package set-home-activity --user 0 ${me.flattenToString()}")
        "Launcher original deshabilitado (${targets.size}); vuelve cuando quieras con 'Restaurar'"
    }

    /** Deshabilita bloatware seguro de [DebloatRules.SAFE_BLOAT] protegiendo servicios críticos y dueños de Wi-Fi. */
    fun debloat(ctx: Context, onApproval: () -> Unit): String = run(ctx, onApproval) { adb ->
        val enabled = parsePackageList(adb.shell("pm list packages -e"))
        val extra = protectedFromDevice(ctx) + wifiOwners(adb)
        val targets = DebloatRules.filterDebloatTargets(enabled, extra)
        if (targets.isEmpty()) return@run "No se encontró bloatware pendiente para deshabilitar"
        val prefs = ShelfPrefs(ctx)
        val disabled = HashSet(prefs.disabledByLienzo)
        var count = 0
        for (p in targets) {
            adb.shell("pm disable-user --user 0 $p")
            adb.shell("am force-stop $p")
            disabled += p
            count++
        }
        prefs.disabledByLienzo = disabled
        "Se deshabilitaron $count apps de fábrica (reversible)"
    }

    /** Re-habilita todas las apps deshabilitadas por Lienzo (launcher original y bloatware). */
    fun restoreDebloat(ctx: Context, onApproval: () -> Unit): String = run(ctx, onApproval) { adb ->
        val prefs = ShelfPrefs(ctx)
        val disabledOut = adb.shell("pm list packages -d")
        val candidates = (prefs.disabledByLienzo + DebloatRules.KNOWN_STOCK_LAUNCHERS + DebloatRules.SAFE_BLOAT)
        var count = 0
        for (p in candidates) {
            if (disabledOut.contains("package:$p\n") || disabledOut.trimEnd().endsWith("package:$p")) {
                adb.shell("pm enable $p")
                count++
            }
        }
        prefs.disabledByLienzo = emptySet()
        if (count == 0) "No había apps deshabilitadas por restaurar"
        else "Se restauraron $count apps deshabilitadas"
    }

    /** Autoriza solo la instalación de actualizaciones de Lienzo vía ADB local (para el panel Actualizar sin salir). */
    fun grantInstallPermission(ctx: Context, onApproval: () -> Unit): String = run(ctx, onApproval) { adb ->
        val pkg = ctx.packageName
        adb.shell("appops set $pkg REQUEST_INSTALL_PACKAGES allow")
        adb.shell("cmd appops set $pkg REQUEST_INSTALL_PACKAGES allow")
        "Permiso de instalación concedido"
    }

    /** Cierra apps de usuario o de streaming preinstaladas en segundo plano vía `am force-stop` (sin tocar reproducción activa ni servicios críticos). */
    fun forceStopBackgroundApps(ctx: Context, onApproval: () -> Unit): String = run(ctx, onApproval) { adb ->
        if (MemoryCleaner.playbackActive(ctx)) return@run "Hay reproducción activa. Pausa el contenido antes de cerrar apps."
        val candidates = parsePackageList(adb.shell("pm list packages -3")).toSet() + DebloatRules.KNOWN_SYSTEM_STREAMING_APPS
        val runningProcs = adb.shell("ps -A -o NAME").lineSequence().map { it.trim().substringBefore(':') }.filter { it.isNotEmpty() }.toSet()
        val runningCandidates = candidates.filter { it in runningProcs }
        val extra = protectedFromDevice(ctx)
        val targets = DebloatRules.filterForceStopTargets(runningCandidates, extra)
        if (targets.isEmpty()) return@run "No hay apps de usuario activas en segundo plano"
        for (p in targets) adb.shell("am force-stop $p")
        "Se cerraron ${targets.size} apps en segundo plano"
    }

    /** Devuelve el inicio al launcher del fabricante (o al primero que no sea Lienzo) y lo abre. */
    fun restoreStock(ctx: Context, onApproval: () -> Unit): String = run(ctx, onApproval) { adb ->
        val prefs = ShelfPrefs(ctx)
        prefs.keyServiceDesired = false
        val disabled = adb.shell("pm list packages -d")
        for (p in (DebloatRules.KNOWN_STOCK_LAUNCHERS + prefs.disabledByLienzo)) {
            if (disabled.contains("package:$p\n") || disabled.trimEnd().endsWith("package:$p")) adb.shell("pm enable $p")
        }
        prefs.disabledByLienzo = emptySet()
        val stock = stockHome(ctx) ?: return@run "No hay otro launcher instalado"
        adb.shell("cmd package set-home-activity --user 0 ${stock.flattenToString()}")
        adb.shell("am start -a android.intent.action.MAIN -c android.intent.category.HOME")
        "Inicio devuelto a ${appLabel(ctx, stock.packageName)}"
    }

    private fun parsePackageList(out: String): List<String> =
        out.lineSequence().mapNotNull { l ->
            val t = l.trim()
            if (t.startsWith("package:")) t.removePrefix("package:") else null
        }.toList()

    private fun protectedFromDevice(ctx: Context): Set<String> {
        val out = HashSet<String>()
        out += ctx.packageName
        runCatching {
            val cr = ctx.contentResolver
            Settings.Secure.getString(cr, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.substringBefore('/')?.takeIf { it.isNotBlank() }?.let { out += it }
            Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
                .split(':').mapNotNull { it.substringBefore('/').trim().takeIf { s -> s.isNotEmpty() } }
                .forEach { out += it }
            val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            am?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                ?.mapNotNull { it.resolveInfo?.serviceInfo?.packageName }
                ?.forEach { out += it }
            val tim = ctx.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
            tim?.tvInputList?.mapNotNull { it.serviceInfo?.packageName }?.forEach { out += it }
        }
        return out
    }

    private fun wifiOwners(adb: LocalAdb): Set<String> = try {
        val dump = adb.shell("dumpsys wifi")
        val out = HashSet<String>()
        val rx = Regex("""(?:creatorName|lastUpdateName)=([a-zA-Z0-9_.]+)""")
        for (m in rx.findAll(dump)) out += m.groupValues[1]
        out
    } catch (_: Exception) { emptySet() }

    /** Launcher original: primero los conocidos de Google TV y Android TV, luego cualquier otro HOME que no sea Lienzo. */
    private fun stockHome(ctx: Context): ComponentName? {
        val pm = ctx.packageManager
        val homes = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_ALL)
            .map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
            .filter { it.packageName != ctx.packageName && !it.className.endsWith("FallbackHome") }
        return homes.firstOrNull { it.packageName in DebloatRules.KNOWN_STOCK_LAUNCHERS } ?: homes.firstOrNull()
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
}
