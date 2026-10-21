package cl.deco.shelf.update

import android.content.Context
import android.os.Build
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Consulta el último release de GitHub (HttpURLConnection, ETag, User-Agent) y lee version.json. Bloqueante: llamar fuera del main thread.
 * Sin servicios ni alarmas: solo corre cuando el home lo pide (al volver, como máximo cada 24 h) o desde Ajustes.
 */
class UpdateChecker(ctx: Context, private val store: UpdateStore = UpdateStore(ctx)) {
    private val app = ctx.applicationContext

    sealed class Outcome {
        data class Available(val info: UpdateInfo) : Outcome()
        /** Nada que instalar; [text] es el estado para mostrar. */
        data class UpToDate(val text: String) : Outcome()
        data class Failed(val text: String) : Outcome()
    }

    fun installedCode(): Long = try {
        app.packageManager.getPackageInfo(app.packageName, 0).longVersionCode
    } catch (_: Exception) { 0L }

    private fun installedName(): String = try { app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "?" } catch (_: Exception) { "?" }

    fun check(): Outcome {
        UpdateInstaller.cleanup(app) // restos de un intento anterior
        return try {
            fetch()
        } catch (e: IOException) {
            Outcome.Failed("Sin conexión con GitHub")
        } catch (e: Exception) {
            Outcome.Failed("No se pudo leer la versión publicada")
        }
    }

    private fun fetch(): Outcome {
        val cached = store.info
        val etag = store.etag.takeIf { cached != null }
        val conn = open(UpdateRules.LATEST_URL, "application/vnd.github+json", etag)
        try {
            val code = conn.responseCode
            if (code == 304 && cached != null) return decide(cached)
            if (code == 404) { store.etag = null; store.info = null; return Outcome.UpToDate("Sin versiones publicadas") }
            if (code == 403 || code == 429) return Outcome.Failed("GitHub limitó las consultas, intenta más tarde")
            if (code != 200) return Outcome.Failed("GitHub respondió HTTP $code")
            val newEtag = conn.getHeaderField("ETag")
            val assets = UpdateRules.parseAssets(readLimited(conn, 512 * 1024)) ?: return Outcome.UpToDate("Sin versiones publicadas")
            val vj = UpdateRules.pickVersionJson(assets) ?: return Outcome.Failed("El release no trae version.json")
            if (!UpdateRules.isTrustedAssetUrl(vj.url, requireRepoPath = true)) return Outcome.Failed("Origen de version.json no confiable")
            val meta = UpdateRules.parseVersionJson(download(vj.url, 64 * 1024)) ?: return Outcome.Failed("version.json inválido")
            val apk = UpdateRules.pickApk(assets, meta.versionName) ?: return Outcome.Failed("El release no trae el APK ${meta.versionName}")
            if (!UpdateRules.isTrustedAssetUrl(apk.url, requireRepoPath = true)) return Outcome.Failed("Origen del APK no confiable")
            val info = UpdateInfo.of(meta, apk.url, apk.size)
            store.info = info
            store.etag = newEtag
            return decide(info)
        } finally {
            conn.disconnect()
        }
    }

    private fun decide(info: UpdateInfo): Outcome {
        val inst = installedCode()
        return when {
            UpdateRules.isApplicable(info, inst, Build.VERSION.SDK_INT) -> Outcome.Available(info)
            UpdateRules.isNewer(info.versionCode, inst) -> Outcome.UpToDate("Hay una versión nueva que requiere Android ${info.minSdk} o superior")
            else -> Outcome.UpToDate("Al día (v${installedName()})")
        }
    }

    private fun open(url: String, accept: String, etag: String?): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", userAgent(app))
        c.setRequestProperty("Accept", accept)
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        if (etag != null) c.setRequestProperty("If-None-Match", etag)
        return c
    }

    /** Descarga un asset pequeño (version.json); verifica que la redirección siga en https y en GitHub. */
    private fun download(url: String, limit: Int): String {
        val c = open(url, "application/octet-stream", null)
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            if (c.url.protocol != "https" || !UpdateRules.isTrustedHost(c.url.host)) throw IOException("redirección no confiable")
            return readLimited(c, limit)
        } finally { c.disconnect() }
    }

    private fun readLimited(c: HttpURLConnection, limit: Int): String {
        val out = java.io.ByteArrayOutputStream()
        c.inputStream.use { ins ->
            val buf = ByteArray(8192)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > limit) throw IOException("respuesta demasiado grande")
            }
        }
        return out.toString("UTF-8")
    }

    companion object {
        fun userAgent(ctx: Context): String {
            val v = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName } catch (_: Exception) { null } ?: "?"
            return "Lienzo/$v (Android TV; +https://github.com/${UpdateRules.REPO})"
        }
    }
}
