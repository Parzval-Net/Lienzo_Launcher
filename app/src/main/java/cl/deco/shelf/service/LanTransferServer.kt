package cl.deco.shelf.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import cl.deco.shelf.data.art.MediaImporter
import cl.deco.shelf.data.art.VideoRules
import cl.deco.shelf.ui.BackupManager
import cl.deco.shelf.ui.Es
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Reglas puras (testeables en JVM) para validar token, query, nombres y extensiones del servidor LAN. */
object LanTransferRules {
    private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "webp", "bmp")

    fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (pair in rawQuery.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val k = decode(if (eq >= 0) pair.substring(0, eq) else pair)
            val v = if (eq >= 0) decode(pair.substring(eq + 1)) else ""
            if (k.isNotEmpty()) out[k] = v
        }
        return out
    }

    private fun decode(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (_: Exception) { s }

    fun validToken(expected: String, provided: String?): Boolean {
        if (expected.isEmpty() || provided == null || expected.length != provided.length) return false
        var diff = 0
        for (i in expected.indices) diff = diff or (expected[i].code xor provided[i].code)
        return diff == 0
    }

    fun sanitizeUploadName(raw: String?): String {
        val base = raw.orEmpty().replace('\\', '/').substringAfterLast('/').trim()
        if (base.isEmpty() || base.all { it == '.' }) return ""
        val clean = base.replace("..", "_").map { c ->
            if (c.code < 32 || c in "<>:\"|?*") '_' else c
        }.joinToString("").trim().trimStart('.')
        return clean.take(100)
    }

    fun isImageExt(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase(Locale.ROOT) in IMAGE_EXTS

    fun isVideoExt(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase(Locale.ROOT) in VideoRules.EXTENSIONS

    fun isAllowedMediaExt(name: String): Boolean = isImageExt(name) || isVideoExt(name)
}

/**
 * Micro-servidor HTTP efímero en la LAN local (solo `ServerSocket` + `java.io`).
 * Se abre únicamente mientras [LanTransferPanel] está visible, exige token aleatorio de un uso de sesión
 * en cada petición y se cierra tras 5 min sin actividad o al salir del panel.
 */
class LanTransferServer(
    private val ctx: Context,
    private val onMediaImported: (String) -> Unit,
    private val onBackupRestored: () -> Unit,
    private val onStatus: (String) -> Unit,
    private val onExpired: () -> Unit,
) {
    private val app = ctx.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "lienzo-lan").apply { isDaemon = true } }
    private val running = AtomicBoolean(false)
    private var server: ServerSocket? = null
    private val token = ByteArray(4).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    @Volatile private var lastActivityMs = System.currentTimeMillis()
    private val expireCheck = object : Runnable {
        override fun run() {
            if (!running.get()) return
            if (System.currentTimeMillis() - lastActivityMs >= IDLE_TIMEOUT_MS) {
                stop()
                onExpired()
            } else {
                main.postDelayed(this, 10_000L)
            }
        }
    }

    var url: String = ""
        private set

    fun start(): Result<String> {
        if (running.get()) return Result.success(url)
        val ip = findLanIpv4() ?: return Result.failure(IOException(if (Es.english) "No local Wi-Fi or Ethernet IP found" else "Sin IP local de Wi-Fi o Ethernet"))
        return try {
            val ss = ServerSocket(0, 8, InetAddress.getByName(ip))
            server = ss
            running.set(true)
            lastActivityMs = System.currentTimeMillis()
            url = "http://$ip:${ss.localPort}/?t=$token"
            main.postDelayed(expireCheck, 10_000L)
            Thread({
                while (running.get()) {
                    try {
                        val sock = ss.accept()
                        pool.execute { handle(sock) }
                    } catch (_: IOException) {
                        break
                    }
                }
            }, "lienzo-lan-accept").apply { isDaemon = true }.start()
            Result.success(url)
        } catch (e: Exception) {
            stop()
            Result.failure(e)
        }
    }

    fun remainingSeconds(): Int = ((IDLE_TIMEOUT_MS - (System.currentTimeMillis() - lastActivityMs)) / 1000L).toInt().coerceAtLeast(0)

    fun stop() {
        if (!running.getAndSet(false) && server == null) return
        main.removeCallbacks(expireCheck)
        runCatching { server?.close() }
        server = null
        pool.shutdownNow()
        app.cacheDir.listFiles { f -> f.name.startsWith("lan_") }?.forEach { it.delete() }
    }

    private fun handle(sock: Socket) {
        sock.soTimeout = 30_000
        try {
            sock.use { s ->
                val ins = BufferedInputStream(s.getInputStream())
                val out = s.getOutputStream()
                val head = readHeaders(ins) ?: return
                val lines = head.split("\r\n")
                val reqParts = lines.firstOrNull()?.split(' ') ?: return
                if (reqParts.size < 2) return
                val method = reqParts[0].uppercase(Locale.ROOT)
                val target = reqParts[1]
                val qIdx = target.indexOf('?')
                val path = if (qIdx >= 0) target.substring(0, qIdx) else target
                val query = LanTransferRules.parseQuery(if (qIdx >= 0) target.substring(qIdx + 1) else null)

                if (!LanTransferRules.validToken(token, query["t"])) {
                    writeText(out, 403, "Forbidden", "text/plain; charset=utf-8", "Forbidden")
                    return
                }
                lastActivityMs = System.currentTimeMillis()

                val contentLen = lines.drop(1)
                    .firstOrNull { it.lowercase(Locale.ROOT).startsWith("content-length:") }
                    ?.substringAfter(':')?.trim()?.toLongOrNull() ?: 0L

                when {
                    method == "GET" && path == "/" ->
                        writeText(out, 200, "OK", "text/html; charset=utf-8", buildHtml(token))
                    method == "GET" && path == "/backup" -> {
                        val json = BackupManager.exportJson(app)
                        val bytes = json.toByteArray(Charsets.UTF_8)
                        writeHeaders(out, 200, "OK", "application/json; charset=utf-8", bytes.size.toLong(),
                            extra = "Content-Disposition: attachment; filename=\"lienzo-backup.json\"\r\n")
                        out.write(bytes)
                        out.flush()
                    }
                    method == "POST" && path == "/restore" -> {
                        if (contentLen <= 0L || contentLen > MAX_BACKUP_BYTES) {
                            writeText(out, 400, "Bad Request", "text/plain; charset=utf-8", "Invalid size")
                            return
                        }
                        val body = readExactBytes(ins, contentLen.toInt()).toString(Charsets.UTF_8)
                        try {
                            BackupManager.restoreFromJson(app, body)
                            writeText(out, 200, "OK", "text/plain; charset=utf-8", if (Es.english) "Backup restored" else "Respaldo restaurado")
                            main.post {
                                onStatus(if (Es.english) "Backup restored, reloading..." else "Respaldo restaurado, recargando...")
                                onBackupRestored()
                            }
                        } catch (e: Exception) {
                            writeText(out, 400, "Bad Request", "text/plain; charset=utf-8", "Invalid backup: ${e.message}")
                        }
                    }
                    method == "POST" && path == "/upload" -> handleUpload(ins, out, query, contentLen)
                    else -> writeText(out, 404, "Not Found", "text/plain; charset=utf-8", "Not found")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "LAN req: $e")
        }
    }

    private fun handleUpload(ins: InputStream, out: OutputStream, query: Map<String, String>, contentLen: Long) {
        val rawName = query["name"]
        val name = LanTransferRules.sanitizeUploadName(rawName)
        if (name.isEmpty() || !LanTransferRules.isAllowedMediaExt(name)) {
            writeText(out, 400, "Bad Request", "text/plain; charset=utf-8", "Unsupported file type")
            return
        }
        val max = if (LanTransferRules.isImageExt(name)) MAX_IMAGE_BYTES else MAX_VIDEO_BYTES
        if (contentLen <= 0L || contentLen > max) {
            writeText(out, 413, "Payload Too Large", "text/plain; charset=utf-8", "File too large (max ${max / (1024 * 1024)} MB)")
            return
        }
        val col = query["col"].orEmpty()
        val tmp = File.createTempFile("lan_", "." + name.substringAfterLast('.', "bin"), app.cacheDir)
        try {
            main.post { onStatus((if (Es.english) "Receiving " else "Recibiendo ") + name) }
            tmp.outputStream().use { fout ->
                val buf = ByteArray(32 * 1024)
                var left = contentLen
                while (left > 0) {
                    val toRead = minOf(buf.size.toLong(), left).toInt()
                    val r = ins.read(buf, 0, toRead)
                    if (r < 0) throw IOException("EOF")
                    fout.write(buf, 0, r)
                    left -= r
                    lastActivityMs = System.currentTimeMillis()
                }
            }
            val res = MediaImporter(app).importLocalFile(tmp, name, col)
            res.fold(
                onSuccess = { msg ->
                    main.post { onStatus(msg); onMediaImported(name) }
                    writeText(out, 200, "OK", "text/plain; charset=utf-8", msg)
                },
                onFailure = { err ->
                    val msg = err.message ?: err.javaClass.simpleName
                    main.post { onStatus("Error: $msg") }
                    writeText(out, 422, "Unprocessable", "text/plain; charset=utf-8", msg)
                },
            )
        } finally {
            tmp.delete()
        }
    }

    private fun readHeaders(ins: InputStream): String? {
        val buf = ByteArrayOutputStream(512)
        var state = 0
        while (buf.size() < 8192) {
            val b = ins.read()
            if (b < 0) return null
            buf.write(b)
            state = when {
                state == 0 && b == '\r'.code -> 1
                state == 1 && b == '\n'.code -> 2
                state == 2 && b == '\r'.code -> 3
                state == 3 && b == '\n'.code -> return buf.toString("ISO_8859_1").dropLast(4)
                b == '\r'.code -> 1
                else -> 0
            }
        }
        return null
    }

    private fun readExactBytes(ins: InputStream, len: Int): ByteArray {
        val out = ByteArray(len)
        var off = 0
        while (off < len) {
            val r = ins.read(out, off, len - off)
            if (r < 0) throw IOException("EOF")
            off += r
        }
        return out
    }

    private fun writeHeaders(out: OutputStream, code: Int, reason: String, mime: String, len: Long, extra: String = "") {
        val h = "HTTP/1.1 $code $reason\r\nContent-Type: $mime\r\nContent-Length: $len\r\nConnection: close\r\nCache-Control: no-store\r\n$extra\r\n"
        out.write(h.toByteArray(Charsets.ISO_8859_1))
    }

    private fun writeText(out: OutputStream, code: Int, reason: String, mime: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        writeHeaders(out, code, reason, mime, bytes.size.toLong())
        out.write(bytes)
        out.flush()
    }

    private fun findLanIpv4(): String? = try {
        val candidates = ArrayList<String>()
        val ifaces = NetworkInterface.getNetworkInterfaces()
        while (ifaces != null && ifaces.hasMoreElements()) {
            val ni = ifaces.nextElement()
            if (!ni.isUp || ni.isLoopback || ni.isVirtual) continue
            val addrs = ni.inetAddresses
            while (addrs.hasMoreElements()) {
                val a = addrs.nextElement()
                if (a is Inet4Address && !a.isLoopbackAddress && a.isSiteLocalAddress) {
                    candidates += a.hostAddress.orEmpty()
                }
            }
        }
        candidates.firstOrNull { it.startsWith("192.168.") }
            ?: candidates.firstOrNull { it.startsWith("10.") }
            ?: candidates.firstOrNull()
    } catch (_: Exception) { null }

    private fun buildHtml(tok: String): String {
        val en = Es.english
        val title = if (en) "Lienzo · Phone Transfer" else "Lienzo · Enviar desde celular"
        val sub = if (en) "Same Wi-Fi only · session closes automatically" else "Solo en tu red Wi-Fi · la sesión se cierra sola"
        val hMedia = if (en) "Wallpapers & Videos" else "Fondos y videos"
        val pCol = if (en) "Collection (optional, e.g. Nature)" else "Colección (opcional, ej. Naturaleza)"
        val bSend = if (en) "Upload selected" else "Subir seleccionados"
        val hBak = if (en) "Settings Backup" else "Respaldo de ajustes"
        val bDl = if (en) "Download backup JSON" else "Descargar respaldo JSON"
        val bRestore = if (en) "Restore backup" else "Restaurar respaldo"
        return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>$title</title>
<style>
body{font-family:system-ui,-apple-system,sans-serif;background:#0f121a;color:#f2f4f8;margin:0;padding:18px;max-width:520px;margin:auto}
.card{background:#1b2130;border:1px solid #2d364d;border-radius:16px;padding:18px;margin-bottom:16px}
h1{font-size:20px;margin:0 0 4px}p.sub{color:#9aa6be;font-size:13px;margin:0 0 16px}
h2{font-size:16px;margin:0 0 10px}
input[type=text],input[type=file]{width:100%;box-sizing:border-box;padding:10px;border-radius:10px;border:1px solid #38435e;background:#111621;color:#fff;margin-bottom:10px}
button,a.btn{display:block;width:100%;box-sizing:border-box;text-align:center;text-decoration:none;background:#ff7a1a;color:#111;font-weight:600;border:0;border-radius:10px;padding:12px;font-size:15px;cursor:pointer}
a.btn.sec{background:#2d364d;color:#fff;margin-bottom:12px}
#log{font-size:13px;color:#b8c4da;white-space:pre-wrap;margin-top:10px}
progress{width:100%;height:8px;margin-top:8px;display:none}
</style></head><body>
<h1>$title</h1><p class="sub">$sub</p>
<div class="card"><h2>$hMedia</h2>
<input id="col" type="text" placeholder="$pCol">
<input id="files" type="file" multiple accept=".jpg,.jpeg,.png,.webp,.bmp,.mp4,.m4v,.mov,.mkv,image/*,video/*">
<button onclick="up()">$bSend</button>
<progress id="pb" max="100" value="0"></progress>
<div id="log"></div></div>
<div class="card"><h2>$hBak</h2>
<a class="btn sec" href="/backup?t=$tok">$bDl</a>
<input id="bak" type="file" accept=".json,application/json">
<button onclick="rst()">$bRestore</button>
<div id="blog" style="font-size:13px;color:#b8c4da;margin-top:8px"></div></div>
<script>
const T="$tok";
function up(){
  const fs=document.getElementById('files').files, col=encodeURIComponent(document.getElementById('col').value||'');
  const log=document.getElementById('log'), pb=document.getElementById('pb');
  if(!fs.length){log.textContent='No files';return;}
  let i=0; pb.style.display='block'; log.textContent='';
  function next(){
    if(i>=fs.length){pb.style.display='none';return;}
    const f=fs[i++], xhr=new XMLHttpRequest();
    xhr.open('POST','/upload?t='+T+'&name='+encodeURIComponent(f.name)+'&col='+col);
    xhr.upload.onprogress=e=>{if(e.lengthComputable)pb.value=Math.round(e.loaded*100/e.total);};
    xhr.onload=()=>{log.textContent+=f.name+': '+xhr.responseText+'\n';next();};
    xhr.onerror=()=>{log.textContent+=f.name+': error\n';next();};
    xhr.send(f);
  }
  next();
}
function rst(){
  const f=document.getElementById('bak').files[0], blog=document.getElementById('blog');
  if(!f)return;
  f.text().then(txt=>fetch('/restore?t='+T,{method:'POST',body:txt}).then(r=>r.text()).then(t=>{blog.textContent=t;}));
}
</script></body></html>"""
    }

    companion object {
        private const val TAG = "LanTransfer"
        private const val IDLE_TIMEOUT_MS = 5 * 60_000L
        private const val MAX_IMAGE_BYTES = 15L * 1024 * 1024
        private const val MAX_VIDEO_BYTES = 250L * 1024 * 1024
        private const val MAX_BACKUP_BYTES = 512L * 1024
    }
}
