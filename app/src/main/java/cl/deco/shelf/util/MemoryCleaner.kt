package cl.deco.shelf.util

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.provider.Settings
import android.util.Log

/**
 * Liberar memoria: ActivityManager.killBackgroundProcesses (permiso normal KILL_BACKGROUND_PROCESSES) sobre apps de
 * usuario en segundo plano o en caché. El sistema solo mata procesos que no están en primer plano ni percibidos;
 * aun así se aplica [KillPolicy] y se ignoran las apps de sistema (salvo las actualizadas desde la tienda, como YouTube).
 * No usa servicios residentes: se ejecuta al presionar el botón.
 *
 * LIMITACIÓN VERIFICADA en el deco (Android 12, parche 2025-04): ActivityManagerService.killBackgroundProcesses solo acepta
 * paquetes con el mismo appId del llamador, salvo que tenga FORCE_STOP_PACKAGES (permiso de firma) o sea app de sistema.
 * Para el resto responde "Invalid packageName" en logcat y no hace nada, aunque la app vea el paquete (QUERY_ALL_PACKAGES
 * tampoco cambia el resultado). Por eso el efecto real de "Liberar memoria" es soltar las cachés propias, y el panel lo dice.
 */
object MemoryCleaner {
    private const val TAG = "Shelf"

    /**
     * Señal aproximada de reproducción activa. MediaSessionManager.getActiveSessions exige ser listener de
     * notificaciones (permiso especial), así que se usa AudioManager.isMusicActive (sin permisos): vale para
     * video y música, no detecta video silenciado.
     */
    fun playbackActive(ctx: Context): Boolean =
        (ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager).isMusicActive

    /** Paquetes candidatos: procesos visibles para Lienzo en segundo plano/caché más las apps con ícono de TV. */
    fun candidates(ctx: Context): List<String> {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val pm = ctx.packageManager
        val ime = KillPolicy.imePackage(Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD))
        val running = am.runningAppProcesses.orEmpty()
            .filter { it.importance >= ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE }
            .flatMap { it.pkgList.toList() }
        val launchable = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)
            .map { it.activityInfo.packageName }
        return (running + launchable).distinct().filter { pkg ->
            if (KillPolicy.isProtected(pkg, ime, ctx.packageName)) return@filter false
            val f = try { pm.getApplicationInfo(pkg, 0).flags } catch (_: Exception) { return@filter false }
            val system = f and ApplicationInfo.FLAG_SYSTEM != 0 && f and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0
            !system
        }
    }

    /** Pide cerrar los procesos en segundo plano de los candidatos; devuelve cuántos paquetes se solicitaron. */
    fun free(ctx: Context): Int {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val targets = candidates(ctx)
        for (p in targets) try { am.killBackgroundProcesses(p) } catch (e: Exception) { Log.w(TAG, "kill $p: $e") }
        Log.d(TAG, "Liberar memoria: ${targets.size} paquetes solicitados")
        return targets.size
    }
}
