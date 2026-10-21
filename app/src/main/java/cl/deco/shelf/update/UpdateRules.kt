package cl.deco.shelf.update

import org.json.JSONObject
import java.io.InputStream
import java.net.URI
import java.security.MessageDigest

/**
 * Lógica pura (JVM) del actualizador: versiones, version.json, release de GitHub, confianza de URLs, espacio, hash,
 * firma y reglas antifatiga del chip. Sin Android: todo está cubierto por tests.
 */

/** Contenido de version.json, el asset que acompaña a cada release. */
data class VersionMeta(val versionCode: Long, val versionName: String, val sha256: String, val minSdk: Int, val notes: String)

/** Versión publicada lista para ofrecer: version.json + el asset del APK. */
data class UpdateInfo(
    val versionCode: Long, val versionName: String, val sha256: String, val minSdk: Int, val notes: String,
    val apkUrl: String, val apkSize: Long,
) {
    fun toJson(): String = JSONObject().put("c", versionCode).put("n", versionName).put("h", sha256).put("s", minSdk)
        .put("t", notes).put("u", apkUrl).put("z", apkSize).toString()

    /** Texto del chip: "Actualización v1.1.0 disponible". */
    fun chipText() = "Actualización ${UpdateRules.displayVersion(versionName)} disponible"

    companion object {
        fun of(m: VersionMeta, apkUrl: String, apkSize: Long) = UpdateInfo(m.versionCode, m.versionName, m.sha256, m.minSdk, m.notes, apkUrl, apkSize)

        fun fromJson(s: String?): UpdateInfo? = try {
            if (s.isNullOrEmpty()) null else JSONObject(s).let {
                UpdateInfo(it.getLong("c"), it.getString("n"), it.getString("h"), it.getInt("s"), it.optString("t"), it.getString("u"), it.getLong("z"))
            }
        } catch (_: Exception) { null }
    }
}

data class ReleaseAsset(val name: String, val url: String, val size: Long)

object UpdateRules {
    const val REPO = "Parzval-Net/Lienzo_Launcher"
    const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"
    const val MAX_APK_BYTES = 100L * 1024 * 1024
    const val SPACE_MARGIN_BYTES = 50L * 1024 * 1024

    private val SHA256 = Regex("[0-9a-fA-F]{64}")
    private val NAME = Regex("[0-9A-Za-z._+-]{1,32}")
    private val TRUSTED_HOSTS = setOf(
        "github.com", "api.github.com", "objects.githubusercontent.com",
        "release-assets.githubusercontent.com", "github-releases.githubusercontent.com",
    )

    // ---- Versiones ----

    fun isNewer(remoteCode: Long, installedCode: Long) = remoteCode > installedCode

    /** La versión publicada aplica a este equipo: es mayor y su minSdk lo permite. */
    fun isApplicable(i: UpdateInfo, installedCode: Long, sdk: Int) = isNewer(i.versionCode, installedCode) && i.minSdk <= sdk

    fun displayVersion(name: String) = if (name.startsWith("v", ignoreCase = true)) name else "v$name"

    // ---- version.json ----

    /** Devuelve null si falta un campo o es inválido (código, nombre, sha256 de 64 hex). minSdk por defecto 1; notas hasta 600 caracteres. */
    fun parseVersionJson(text: String): VersionMeta? = try {
        val o = JSONObject(text)
        val code = o.getLong("versionCode")
        val name = o.getString("versionName")
        val sha = o.getString("sha256")
        val minSdk = if (o.has("minSdk")) o.getInt("minSdk") else 1
        if (code <= 0 || !NAME.matches(name) || !SHA256.matches(sha) || minSdk < 1) null
        else VersionMeta(code, name, sha.lowercase(), minSdk, o.optString("notes", "").take(600))
    } catch (_: Exception) { null }

    // ---- Release de GitHub ----

    /** Assets de la respuesta de /releases/latest; null si es borrador, prerelease o no se puede leer. */
    fun parseAssets(text: String): List<ReleaseAsset>? = try {
        val o = JSONObject(text)
        if (o.optBoolean("draft", false) || o.optBoolean("prerelease", false)) null
        else {
            val a = o.getJSONArray("assets")
            (0 until a.length()).map { a.getJSONObject(it) }.map { ReleaseAsset(it.getString("name"), it.getString("browser_download_url"), it.optLong("size", -1)) }
        }
    } catch (_: Exception) { null }

    /** El APK del release: `Lienzo-<versionName>.apk`; si no existe, ninguno (no se adivina). */
    fun pickApk(assets: List<ReleaseAsset>, versionName: String) = assets.firstOrNull { it.name == "Lienzo-$versionName.apk" }

    fun pickVersionJson(assets: List<ReleaseAsset>) = assets.firstOrNull { it.name == "version.json" }

    /** Solo https hacia GitHub (o su CDN de releases); el APK además debe venir del repositorio de Lienzo. */
    fun isTrustedHost(host: String?) = host != null && host.lowercase() in TRUSTED_HOSTS

    fun isTrustedAssetUrl(url: String, requireRepoPath: Boolean = true): Boolean = try {
        val u = URI(url)
        u.scheme == "https" && isTrustedHost(u.host) && u.userInfo == null &&
            (!requireRepoPath || (u.host.equals("github.com", true) && u.path.startsWith("/$REPO/releases/download/", ignoreCase = true)))
    } catch (_: Exception) { false }

    // ---- Espacio ----

    /** Hay espacio para descargar y para que el instalador copie el APK a su sesión (2x) más un margen. */
    fun hasSpace(apkSize: Long, freeBytes: Long) =
        apkSize in 1..MAX_APK_BYTES && freeBytes >= apkSize * 2 + SPACE_MARGIN_BYTES

    // ---- Hash y firma ----

    fun sha256Hex(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun sha256Hex(input: InputStream, onChunk: (Int) -> Unit = {}): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(32 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n); onChunk(n)
        }
        return hex(md.digest())
    }

    private fun hex(d: ByteArray) = d.joinToString("") { "%02x".format(it) }

    fun hashMatches(expected: String, actual: String) =
        SHA256.matches(expected) && MessageDigest.isEqual(expected.lowercase().toByteArray(), actual.lowercase().toByteArray())

    fun fingerprints(certs: List<ByteArray>): Set<String> = certs.map { sha256Hex(it) }.toSet()

    /** Mismo conjunto exacto de certificados de firma; un conjunto vacío nunca es válido. */
    fun sameSigners(installed: Set<String>, candidate: Set<String>) = installed.isNotEmpty() && installed == candidate

    // ---- Frecuencia (mismas reglas antifatiga que Salud del deco) ----

    /** Duraciones: búsqueda automática cada 24 h; el chip se oculta solo a las 12 h; no vuelve antes de 24 h desde que apareció. */
    class Timing(val checkGapMs: Long, val showMaxMs: Long, val cooldownMs: Long) {
        companion object { private const val H = 3_600_000L; val NORMAL = Timing(24 * H, 12 * H, 24 * H) }
    }

    /** Estado persistido (UpdateStore). 0 = nunca. */
    data class State(
        val lastCheckMs: Long = 0L,     // última búsqueda (automática o manual)
        val shownMs: Long = 0L,         // desde cuándo está visible el chip (0 = oculto)
        val lastNoticeMs: Long = 0L,    // último aviso mostrado
        val snoozeUntilMs: Long = 0L,   // "Más tarde"
        val availableCode: Long = 0L,   // versionCode disponible según la última búsqueda (0 = ninguno)
    )

    /** Búsqueda automática: solo si está activada, sin reproducción ni modo arte (`blocked`) y pasadas 24 h desde la anterior. */
    fun canCheck(s: State, now: Long, t: Timing, blocked: Boolean, auto: Boolean) =
        auto && !blocked && (s.lastCheckMs == 0L || now < s.lastCheckMs || now - s.lastCheckMs >= t.checkGapMs)

    /** Resultado de una búsqueda: [availableCode] es el versionCode aplicable o 0 si no hay nada. */
    fun onCheck(s: State, now: Long, t: Timing, availableCode: Long): State {
        var n = s.copy(lastCheckMs = now, availableCode = availableCode)
        if (n.shownMs > 0 && now - n.shownMs >= t.showMaxMs) n = n.copy(shownMs = 0L)
        if (availableCode == 0L) return n.copy(shownMs = 0L)
        val cooled = n.lastNoticeMs == 0L || now < n.lastNoticeMs || now - n.lastNoticeMs >= t.cooldownMs
        if (n.shownMs == 0L && cooled && now >= n.snoozeUntilMs) n = n.copy(shownMs = now, lastNoticeMs = now)
        return n
    }

    fun visible(s: State, now: Long, t: Timing) =
        s.availableCode > 0 && s.shownMs > 0 && now - s.shownMs < t.showMaxMs && now >= s.snoozeUntilMs

    /** El usuario abrió el panel: el aviso cumplió su función. */
    fun onOpened(s: State) = s.copy(shownMs = 0L)

    /** "Más tarde": oculto y sin volver antes de [snoozeMs]. */
    fun onDismissed(s: State, now: Long, snoozeMs: Long) = s.copy(shownMs = 0L, snoozeUntilMs = now + snoozeMs)

    /** Ya se instaló esa versión (o una mayor): se borra el aviso. */
    fun afterInstalled(s: State, installedCode: Long) = if (s.availableCode in 1..installedCode) s.copy(availableCode = 0L, shownMs = 0L) else s
}
