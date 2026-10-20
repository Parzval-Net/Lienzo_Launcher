package cl.deco.shelf.util

import android.content.Context

/**
 * Apoyo de "Liberar cachés de Lienzo". Android 12 no deja que Lienzo cierre otras apps (killBackgroundProcesses solo acepta
 * paquetes propios sin FORCE_HANDLE), así que la acción solo vacía las cachés de Lienzo y el panel lo dice. Aquí queda lo
 * único útil: saber si hay reproducción, para no tocar nada mientras alguien ve contenido.
 */
object MemoryCleaner {
    /**
     * Señal aproximada de reproducción activa. MediaSessionManager.getActiveSessions exige ser listener de
     * notificaciones (permiso especial), así que se usa AudioManager.isMusicActive (sin permisos): vale para
     * video y música, no detecta video silenciado.
     */
    fun playbackActive(ctx: Context): Boolean =
        (ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager).isMusicActive
}
