package cl.deco.shelf.util

import android.content.Context
import android.os.PowerManager
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

/**
 * GET con HttpURLConnection para las APIs JSON (Open-Meteo, geocoding, Nager.Date):
 * - Revalidación con ETag / If-Modified-Since: el servidor responde 304 y no se descarga el cuerpo.
 * - Deduplica requests en vuelo a la misma URL (varios llamadores esperan el mismo resultado).
 * - Sin red con la pantalla apagada (no despierta la radio en standby).
 * Bloqueante: llamar fuera del main thread.
 */
object Http {
    class Response(val body: String?, val notModified: Boolean)

    private val inflight = ConcurrentHashMap<String, FutureTask<Response>>()

    /**
     * [revalidate] solo debe ser true si el llamador conserva el cuerpo anterior (cache local): con 304 devuelve
     * `Response(null, true)` y el llamador reutiliza su copia.
     */
    fun get(ctx: Context, url: String, userAgent: String, timeoutMs: Int, revalidate: Boolean): Response {
        val app = ctx.applicationContext
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) throw IOException("pantalla apagada: sin red")
        val key = "$revalidate|$url"
        var mine = false
        val task = inflight.computeIfAbsent(key) { mine = true; FutureTask { fetch(app, url, userAgent, timeoutMs, revalidate) } }
        if (mine) {
            try { task.run() } finally { inflight.remove(key) }
        }
        try {
            return task.get()
        } catch (e: ExecutionException) {
            throw (e.cause as? IOException) ?: IOException(e.cause)
        }
    }

    private fun fetch(ctx: Context, url: String, ua: String, timeoutMs: Int, revalidate: Boolean): Response {
        val sp = ctx.getSharedPreferences("http_validators", Context.MODE_PRIVATE)
        val vk = url.hashCode().toString()
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", ua)
            if (revalidate) {
                sp.getString(vk, null)?.split("\n", limit = 2)?.let { v ->
                    v.getOrNull(0)?.takeIf { it.isNotEmpty() }?.let { conn.setRequestProperty("If-None-Match", it) }
                    v.getOrNull(1)?.takeIf { it.isNotEmpty() }?.let { conn.setRequestProperty("If-Modified-Since", it) }
                }
            }
            val code = conn.responseCode
            if (code == 304) return Response(null, true)
            if (code != 200) throw IOException("HTTP $code")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val etag = conn.getHeaderField("ETag").orEmpty()
            val lm = conn.getHeaderField("Last-Modified").orEmpty()
            val ed = sp.edit()
            if (etag.isNotEmpty() || lm.isNotEmpty()) ed.putString(vk, "$etag\n$lm") else ed.remove(vk)
            ed.apply()
            return Response(body, false)
        } finally {
            conn.disconnect()
        }
    }
}
