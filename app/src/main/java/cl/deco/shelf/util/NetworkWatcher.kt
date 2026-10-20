package cl.deco.shelf.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler

/**
 * Estado de red para el home. Se registra solo mientras MainActivity está visible (start en onStart, stop en onStop), así que
 * en reposo no queda ningún callback vivo. Usa la red por defecto: onLost significa que no hay ninguna con salida.
 * Los callbacks llegan por [handler] (main). "En línea" = red por defecto con capacidad INTERNET (no exige VALIDATED, para no
 * marcar "sin conexión" en redes con portal o validación lenta).
 */
class NetworkWatcher(ctx: Context, private val handler: Handler, private val onChange: (Boolean) -> Unit) {
    private val cm = ctx.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var cb: ConnectivityManager.NetworkCallback? = null

    @Volatile var online = true
        private set

    private fun current(): Boolean {
        val net = cm.activeNetwork ?: return false
        return cm.getNetworkCapabilities(net)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    private fun set(v: Boolean) { if (v != online) { online = v; onChange(v) } }

    fun start() {
        if (cb != null) return
        online = current() // el estado inicial lo lee quien llama (sin callback); solo los cambios posteriores notifican
        val c = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = set(true)
            override fun onLost(network: Network) = set(false)
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                set(caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
        }
        try { cm.registerDefaultNetworkCallback(c, handler); cb = c } catch (_: Exception) { /* sin permiso o servicio caído: queda el estado inicial */ }
    }

    fun stop() {
        cb?.let { try { cm.unregisterNetworkCallback(it) } catch (_: Exception) { } }
        cb = null
    }
}
