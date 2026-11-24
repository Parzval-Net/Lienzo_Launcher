package cl.deco.shelf.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import cl.deco.shelf.ui.newExecutor
import cl.deco.shelf.ui.t
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

/** Receptor explícito (`exported="false"`) para el resultado de `PackageInstaller` (compatible con Android 14+ `FLAG_MUTABLE`). */
class UpdateResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        UpdateInstaller.dispatchResult(context, intent)
    }
}

/**
 * Descarga, verifica e instala el APK. Protecciones, en orden:
 * 1. Solo con espacio suficiente; descarga a cacheDir con progreso y cancelación; tope de tamaño.
 * 2. SHA-256 contra version.json.
 * 3. El APK descargado debe ser cl.deco.shelf, con el versionCode anunciado, mayor al instalado y firmado por el MISMO certificado
 *    que la app instalada (PackageManager.getPackageArchiveInfo + GET_SIGNING_CERTIFICATES). Si no, se borra y se aborta.
 * 4. Instalación con PackageInstaller (sesión) exigiendo confirmación del usuario: nunca silenciosa.
 * 5. El APK se borra al terminar, al cancelar y si algo falla.
 */
class UpdateInstaller(ctx: Context, private val ui: Listener) {

    interface Listener {
        fun onProgress(done: Long, total: Long)
        fun onVerifying()
        /** El APK ya está en la sesión del sistema; falta que el usuario confirme en la pantalla de Android. */
        fun onAwaitingConfirm()
        fun onError(message: String)
    }

    private val app = ctx.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val io = newExecutor()
    @Volatile private var cancelled = false
    @Volatile private var running = false
    private var sessionId = -1
    private var sessionToken = ""

    fun start(info: UpdateInfo) {
        if (running) return
        running = true; cancelled = false
        io.execute {
            try { run(info) } catch (_: Cancelled) { fail(null) } catch (e: Fail) { fail(e.message) }
            catch (e: IOException) { fail(t("Falló la descarga: ${e.message ?: "sin conexión"}", "Download failed: ${e.message ?: "offline"}")) }
            catch (e: SecurityException) {
                fail(t(
                    "Android bloqueó la instalación (${e.message ?: "falta permiso"}). Usa 'Autorizar sin salir' o activa Fuentes desconocidas.",
                    "Android blocked install (${e.message ?: "permission missing"}). Use 'Authorize via local ADB' or allow Unknown sources.",
                ))
            }
            catch (e: Exception) { fail(t("Error inesperado: ${e.javaClass.simpleName}", "Unexpected error: ${e.javaClass.simpleName}")) }
            finally { running = false }
        }
    }

    /** Cancela la descarga en curso (o la sesión sin confirmar) y borra los restos. */
    fun cancel() {
        cancelled = true
        if (!running) { cleanup(app); abandon() }
    }

    /** El panel se cierra: deja de escuchar al instalador y borra los restos. */
    fun release() {
        cancelled = true
        clearActive(this)
        if (!running) cleanup(app)
    }

    private class Fail(msg: String) : Exception(msg)
    private class Cancelled : Exception()

    private fun post(f: () -> Unit) { main.post(f) }
    private fun fail(msg: String?) {
        clearActive(this)
        cleanup(app); abandon()
        if (msg != null) post { ui.onError(msg) }
    }

    private fun run(info: UpdateInfo) {
        val dir = File(app.cacheDir, DIR)
        cleanup(app)
        dir.mkdirs()
        if (!UpdateRules.isTrustedAssetUrl(info.apkUrl)) throw Fail(t("Origen del APK no confiable.", "Untrusted APK source."))
        // 1. Espacio
        val free = StatFs(dir.path).availableBytes
        if (!UpdateRules.hasSpace(info.apkSize, free)) {
            throw Fail(t(
                "Espacio insuficiente: se necesitan ${mb(info.apkSize * 2 + UpdateRules.SPACE_MARGIN_BYTES)} libres y hay ${mb(free)}.",
                "Not enough storage: ${mb(info.apkSize * 2 + UpdateRules.SPACE_MARGIN_BYTES)} required, ${mb(free)} free.",
            ))
        }
        val part = File(dir, "Lienzo-update.apk.part")
        val apk = File(dir, "Lienzo-update.apk")
        download(info, part)
        if (cancelled) throw Cancelled()
        if (!part.renameTo(apk)) throw Fail(t("No se pudo guardar el APK descargado.", "Could not save downloaded APK."))

        post { ui.onVerifying() }
        // 2. Hash
        val actual = apk.inputStream().use { UpdateRules.sha256Hex(it) }
        if (!UpdateRules.hashMatches(info.sha256, actual)) throw Fail(t("El APK descargado no coincide con el SHA-256 publicado. Se borró el archivo.", "Downloaded APK SHA-256 mismatch. File deleted."))
        // 3. Paquete, versión y firma
        verifyArchive(apk, info)
        if (cancelled) throw Cancelled()
        // 4. Instalación (el sistema pide confirmar)
        commit(apk)
        post { ui.onAwaitingConfirm() }
        // El APK ya vive en la sesión del instalador: se borra el archivo de cacheDir
        cleanup(app)
    }

    private fun download(info: UpdateInfo, out: File) {
        val c = URL(info.apkUrl).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 20_000
            c.setRequestProperty("User-Agent", UpdateChecker.userAgent(app))
            c.setRequestProperty("Accept", "application/octet-stream")
            if (c.responseCode != 200) throw Fail(t("GitHub respondió HTTP ${c.responseCode} al descargar el APK.", "GitHub returned HTTP ${c.responseCode} while downloading APK."))
            if (c.url.protocol != "https" || !UpdateRules.isTrustedHost(c.url.host)) throw Fail(t("La descarga se redirigió a un origen no confiable.", "Download redirected to an untrusted host."))
            val declared = c.contentLengthLong
            val total = if (declared > 0) declared else info.apkSize
            if (total > UpdateRules.MAX_APK_BYTES) throw Fail(t("El APK publicado es demasiado grande.", "Published APK exceeds size limit."))
            var done = 0L
            var lastPost = 0L
            c.inputStream.use { ins ->
                out.outputStream().use { os ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        if (cancelled) throw Cancelled()
                        val n = ins.read(buf)
                        if (n < 0) break
                        os.write(buf, 0, n)
                        done += n
                        if (done > UpdateRules.MAX_APK_BYTES) throw Fail(t("El APK supera el tamaño máximo permitido.", "APK exceeds maximum size limit."))
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastPost >= 100) { lastPost = now; val d = done; post { ui.onProgress(d, total) } }
                    }
                    os.fd.sync()
                }
            }
            if (info.apkSize > 0 && done != info.apkSize) throw Fail(t("Descarga incompleta ($done de ${info.apkSize} bytes).", "Incomplete download ($done of ${info.apkSize} bytes)."))
            post { ui.onProgress(done, total) }
        } finally {
            c.disconnect()
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyArchive(apk: File, info: UpdateInfo) {
        val pm = app.packageManager
        val flag = PackageManager.GET_SIGNING_CERTIFICATES
        val cand = pm.getPackageArchiveInfo(apk.path, flag) ?: throw Fail(t("El archivo descargado no es un APK válido.", "Downloaded file is not a valid APK."))
        if (cand.packageName != app.packageName) throw Fail(t("El APK es de otra app (${cand.packageName}).", "APK belongs to a different package (${cand.packageName})."))
        val installed = pm.getPackageInfo(app.packageName, flag)
        if (cand.longVersionCode != info.versionCode) throw Fail(t("La versión del APK no coincide con la anunciada.", "APK versionCode does not match release metadata."))
        if (cand.longVersionCode <= installed.longVersionCode) throw Fail(t("El APK no es más nuevo que la versión instalada.", "APK is not newer than installed version."))
        val a = UpdateRules.fingerprints(signers(installed))
        val b = UpdateRules.fingerprints(signers(cand))
        if (!UpdateRules.sameSigners(a, b)) {
            throw Fail(t("La firma del APK no coincide con la de Lienzo instalada. Se canceló la actualización y se borró el archivo.", "APK signature does not match installed Lienzo. Update aborted."))
        }
    }

    private fun signers(p: android.content.pm.PackageInfo): List<ByteArray> =
        p.signingInfo?.apkContentsSigners?.map { it.toByteArray() }.orEmpty()

    private fun commit(apk: File) {
        val pi = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val id = pi.createSession(params)
        val token = java.lang.Long.toHexString(SecureRandom().nextLong())
        sessionId = id
        sessionToken = token
        setActive(this)
        pi.openSession(id).use { s ->
            apk.inputStream().use { ins ->
                s.openWrite("lienzo.apk", 0, apk.length()).use { os ->
                    ins.copyTo(os)
                    s.fsync(os)
                }
            }
            val cbIntent = Intent(app, UpdateResultReceiver::class.java)
                .setAction("cl.deco.shelf.update.SESSION_$id")
                .putExtra(EXTRA_TOKEN, token)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(app, id, cbIntent, flags)
            UpdateStore(app).installStartedMs = System.currentTimeMillis() // para reabrir Lienzo tras la actualización
            s.commit(pending.intentSender)
        }
    }

    private fun onSessionBroadcast(c: Context, i: Intent) {
        if (i.getStringExtra(EXTRA_TOKEN) != sessionToken) return
        if (i.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) != sessionId) return
        when (val st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION") val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                // Acepta cualquier pantalla de confirmación que resuelva al instalador del sistema del firmware (AOSP, Google TV, TCL)
                if (confirm != null && isSystemActivity(c, confirm)) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        c.startActivity(confirm)
                    } catch (_: Exception) {
                        post { ui.onError(t("No se pudo abrir la confirmación de Android.", "Could not open Android install confirmation.")) }
                    }
                } else {
                    post { ui.onError(t("El sistema no entregó una pantalla de confirmación válida.", "System did not provide a valid confirmation screen.")) }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> { /* la app se reemplaza y el proceso termina */ }
            PackageInstaller.STATUS_FAILURE_ABORTED -> post { ui.onError(t("Instalación cancelada.", "Installation cancelled.")) }
            else -> post {
                ui.onError(t(
                    "Android rechazó la instalación (código $st): ${i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty().take(120)}",
                    "Android rejected installation (code $st): ${i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty().take(120)}",
                ))
            }
        }
    }

    private fun abandon() {
        val id = sessionId
        sessionId = -1
        if (id < 0) return
        try { app.packageManager.packageInstaller.abandonSession(id) } catch (_: Exception) { }
    }

    private fun mb(b: Long) = "%.1f MB".format(b / 1048576.0)

    companion object {
        private const val DIR = "update"
        private const val EXTRA_TOKEN = "cl.deco.shelf.update.TOKEN"
        @Volatile private var active: UpdateInstaller? = null

        private fun setActive(inst: UpdateInstaller) { active = inst }
        private fun clearActive(inst: UpdateInstaller) { if (active === inst) active = null }

        internal fun dispatchResult(ctx: Context, intent: Intent) {
            active?.onSessionBroadcast(ctx, intent)
        }

        private fun isSystemActivity(ctx: Context, intent: Intent): Boolean = try {
            val ri = ctx.packageManager.resolveActivity(intent, 0)
            val flags = ri?.activityInfo?.applicationInfo?.flags ?: 0
            (flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
        } catch (_: Exception) { false }

        /** Borra cualquier APK o descarga parcial de cacheDir/update. */
        fun cleanup(ctx: Context) {
            try { File(ctx.cacheDir, DIR).deleteRecursively() } catch (_: Exception) { }
        }
    }
}
