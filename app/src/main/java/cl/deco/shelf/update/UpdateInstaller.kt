package cl.deco.shelf.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import cl.deco.shelf.ui.newExecutor
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

/**
 * Descarga, verifica e instala el APK. Protecciones, en orden:
 * 1. Solo con espacio suficiente; descarga a cacheDir con progreso y cancelación; tope de tamaño.
 * 2. SHA-256 contra version.json.
 * 3. El APK descargado debe ser cl.deco.shelf, con el versionCode anunciado, mayor al instalado y firmado por el MISMO certificado
 *    que la app instalada (PackageManager.getPackageArchiveInfo + GET_SIGNING_CERTIFICATES). Si no, se borra y se aborta.
 * 4. Instalación con PackageInstaller (sesión) exigiendo confirmación del usuario: nunca silenciosa.
 * 5. El APK se borra al terminar, al cancelar y si algo falla.
 * El hash solo detecta corrupción (version.json viaja en el mismo release); lo que protege de un APK alterado es el certificado.
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
    private var receiver: BroadcastReceiver? = null
    private var sessionId = -1

    fun start(info: UpdateInfo) {
        if (running) return
        running = true; cancelled = false
        io.execute {
            try { run(info) } catch (e: Cancelled) { fail(null) } catch (e: Fail) { fail(e.message) }
            catch (e: IOException) { fail("Falló la descarga: ${e.message ?: "sin conexión"}") }
            catch (e: Exception) { fail("Error inesperado: ${e.javaClass.simpleName}") }
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
        unregister()
        if (!running) cleanup(app)
    }

    private class Fail(msg: String) : Exception(msg)
    private class Cancelled : Exception()

    private fun post(f: () -> Unit) { main.post(f) }
    private fun fail(msg: String?) {
        cleanup(app); abandon()
        if (msg != null) post { ui.onError(msg) }
    }

    private fun run(info: UpdateInfo) {
        val dir = File(app.cacheDir, DIR)
        cleanup(app)
        dir.mkdirs()
        if (!UpdateRules.isTrustedAssetUrl(info.apkUrl)) throw Fail("Origen del APK no confiable.")
        // 1. Espacio
        val free = StatFs(dir.path).availableBytes
        if (!UpdateRules.hasSpace(info.apkSize, free)) {
            throw Fail("Espacio insuficiente: se necesitan ${mb(info.apkSize * 2 + UpdateRules.SPACE_MARGIN_BYTES)} libres y hay ${mb(free)}.")
        }
        val part = File(dir, "Lienzo-update.apk.part")
        val apk = File(dir, "Lienzo-update.apk")
        download(info, part)
        if (cancelled) throw Cancelled()
        if (!part.renameTo(apk)) throw Fail("No se pudo guardar el APK descargado.")

        post { ui.onVerifying() }
        // 2. Hash
        val actual = apk.inputStream().use { UpdateRules.sha256Hex(it) }
        if (!UpdateRules.hashMatches(info.sha256, actual)) throw Fail("El APK descargado no coincide con el SHA-256 publicado. Se borró el archivo.")
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
            if (c.responseCode != 200) throw Fail("GitHub respondió HTTP ${c.responseCode} al descargar el APK.")
            if (c.url.protocol != "https" || !UpdateRules.isTrustedHost(c.url.host)) throw Fail("La descarga se redirigió a un origen no confiable.")
            val declared = c.contentLengthLong
            val total = if (declared > 0) declared else info.apkSize
            if (total > UpdateRules.MAX_APK_BYTES) throw Fail("El APK publicado es demasiado grande.")
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
                        if (done > UpdateRules.MAX_APK_BYTES) throw Fail("El APK supera el tamaño máximo permitido.")
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastPost >= 100) { lastPost = now; val d = done; post { ui.onProgress(d, total) } }
                    }
                    os.fd.sync()
                }
            }
            if (info.apkSize > 0 && done != info.apkSize) throw Fail("Descarga incompleta ($done de ${info.apkSize} bytes).")
            post { ui.onProgress(done, total) }
        } finally {
            c.disconnect()
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyArchive(apk: File, info: UpdateInfo) {
        val pm = app.packageManager
        val flag = PackageManager.GET_SIGNING_CERTIFICATES
        val cand = pm.getPackageArchiveInfo(apk.path, flag) ?: throw Fail("El archivo descargado no es un APK válido.")
        if (cand.packageName != app.packageName) throw Fail("El APK es de otra app (${cand.packageName}).")
        val installed = pm.getPackageInfo(app.packageName, flag)
        if (cand.longVersionCode != info.versionCode) throw Fail("La versión del APK no coincide con la anunciada.")
        if (cand.longVersionCode <= installed.longVersionCode) throw Fail("El APK no es más nuevo que la versión instalada.")
        val a = UpdateRules.fingerprints(signers(installed))
        val b = UpdateRules.fingerprints(signers(cand))
        if (!UpdateRules.sameSigners(a, b)) {
            throw Fail("La firma del APK no coincide con la de Lienzo instalada. Se canceló la actualización y se borró el archivo.")
        }
    }

    private fun signers(p: android.content.pm.PackageInfo): List<ByteArray> =
        p.signingInfo?.apkContentsSigners?.map { it.toByteArray() }.orEmpty()

    private fun commit(apk: File) {
        val pi = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(apk.length())
            // Android 12+: confirmación explícita. Antes de 12 la confirmación del sistema es siempre obligatoria (y este método no existe)
            if (android.os.Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val id = pi.createSession(params)
        sessionId = id
        val action = "cl.deco.shelf.update.RESULT." + java.lang.Long.toHexString(SecureRandom().nextLong())
        register(action, id)
        pi.openSession(id).use { s ->
            apk.inputStream().use { ins ->
                s.openWrite("lienzo.apk", 0, apk.length()).use { os ->
                    ins.copyTo(os)
                    s.fsync(os)
                }
            }
            val pending = PendingIntent.getBroadcast(app, id, Intent(action).setPackage(app.packageName), PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            UpdateStore(app).installStartedMs = System.currentTimeMillis() // para reabrir Lienzo tras la actualización
            s.commit(pending.intentSender)
        }
    }

    /** Receptor dinámico (sin componente en el manifest) con acción aleatoria por sesión; solo atiende el id de su sesión. */
    private fun register(action: String, id: Int) {
        unregister()
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) != id) return
                when (val st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        @Suppress("DEPRECATION") val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                        // Solo la pantalla de confirmación del instalador del sistema; cualquier otra cosa se ignora
                        if (confirm != null && confirm.action == "android.content.pm.action.CONFIRM_INSTALL") {
                            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            try { c.startActivity(confirm) } catch (e: Exception) { post { ui.onError("No se pudo abrir la confirmación de Android.") } }
                        }
                    }
                    PackageInstaller.STATUS_SUCCESS -> { /* la app se reemplaza y el proceso termina */ }
                    PackageInstaller.STATUS_FAILURE_ABORTED -> post { ui.onError("Instalación cancelada.") }
                    else -> post { ui.onError("Android rechazó la instalación (código $st): ${i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty().take(120)}") }
                }
            }
        }
        val f = IntentFilter(action)
        // RECEIVER_NOT_EXPORTED = 4; en Android 12 se ignora, por eso además la acción es secreta y se valida el id de sesión
        app.registerReceiver(r, f, 4)
        receiver = r
    }

    private fun unregister() {
        receiver?.let { try { app.unregisterReceiver(it) } catch (_: Exception) { } }
        receiver = null
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

        /** Borra cualquier APK o descarga parcial de cacheDir/update. */
        fun cleanup(ctx: Context) {
            try { File(ctx.cacheDir, DIR).deleteRecursively() } catch (_: Exception) { }
        }
    }
}
