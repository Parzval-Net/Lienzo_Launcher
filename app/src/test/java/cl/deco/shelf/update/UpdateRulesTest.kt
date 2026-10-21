package cl.deco.shelf.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class UpdateRulesTest {
    private val sha = "a".repeat(64)
    private fun json(code: String = "4", name: String = "1.2.0", hash: String = "\"$sha\"", extra: String = ", \"minSdk\": 31, \"notes\": \"Novedades\"") =
        "{\"versionCode\": $code, \"versionName\": \"$name\", \"sha256\": $hash$extra}"

    // ---- Versiones ----

    @Test fun `solo un versionCode mayor es nuevo`() {
        assertTrue(UpdateRules.isNewer(4, 3))
        assertFalse(UpdateRules.isNewer(3, 3))
        assertFalse(UpdateRules.isNewer(2, 3))
    }

    @Test fun `aplica solo si es mayor y el minSdk lo permite`() {
        val i = UpdateInfo(4, "1.2.0", sha, 31, "", "https://github.com/x", 1)
        assertTrue(UpdateRules.isApplicable(i, 3, 31))
        assertTrue(UpdateRules.isApplicable(i, 3, 35))
        assertFalse(UpdateRules.isApplicable(i, 3, 30))
        assertFalse(UpdateRules.isApplicable(i, 4, 35))
    }

    @Test fun `nombre de version para mostrar`() {
        assertEquals("v1.1.0", UpdateRules.displayVersion("1.1.0"))
        assertEquals("v1.1.0", UpdateRules.displayVersion("v1.1.0"))
        assertEquals("Actualización v1.2.0 disponible", UpdateInfo(4, "1.2.0", sha, 31, "", "u", 1).chipText())
    }

    // ---- version.json ----

    @Test fun `version json valido`() {
        val m = UpdateRules.parseVersionJson(json())!!
        assertEquals(4L, m.versionCode); assertEquals("1.2.0", m.versionName); assertEquals(sha, m.sha256)
        assertEquals(31, m.minSdk); assertEquals("Novedades", m.notes)
    }

    @Test fun `version json normaliza el hash y usa valores por defecto`() {
        val m = UpdateRules.parseVersionJson(json(hash = "\"${"AB".repeat(32)}\"", extra = ""))!!
        assertEquals("ab".repeat(32), m.sha256); assertEquals(1, m.minSdk); assertEquals("", m.notes)
    }

    @Test fun `version json invalido se rechaza`() {
        assertNull(UpdateRules.parseVersionJson("no es json"))
        assertNull(UpdateRules.parseVersionJson("{}"))
        assertNull(UpdateRules.parseVersionJson(json(code = "0")))
        assertNull(UpdateRules.parseVersionJson(json(code = "-1")))
        assertNull(UpdateRules.parseVersionJson(json(hash = "\"abc\"")))
        assertNull(UpdateRules.parseVersionJson(json(hash = "\"${"g".repeat(64)}\"")))
        assertNull(UpdateRules.parseVersionJson(json(name = "1.0 <script>")))
        assertNull(UpdateRules.parseVersionJson(json(extra = ", \"minSdk\": 0")))
    }

    @Test fun `las notas se acotan`() {
        val m = UpdateRules.parseVersionJson(json(extra = ", \"notes\": \"${"x".repeat(2000)}\""))!!
        assertEquals(600, m.notes.length)
    }

    @Test fun `UpdateInfo ida y vuelta por json`() {
        val i = UpdateInfo(4, "1.2.0", sha, 31, "notas", "https://github.com/a", 123)
        assertEquals(i, UpdateInfo.fromJson(i.toJson()))
        assertNull(UpdateInfo.fromJson(null)); assertNull(UpdateInfo.fromJson("basura"))
    }

    // ---- Release de GitHub ----

    private fun release(draft: Boolean = false, pre: Boolean = false) = """{"draft":$draft,"prerelease":$pre,"assets":[
        {"name":"Lienzo-1.2.0.apk","browser_download_url":"https://github.com/Parzval-Net/Lienzo_Launcher/releases/download/v1.2.0/Lienzo-1.2.0.apk","size":600000},
        {"name":"version.json","browser_download_url":"https://github.com/Parzval-Net/Lienzo_Launcher/releases/download/v1.2.0/version.json","size":200},
        {"name":"SHA256SUMS","browser_download_url":"https://github.com/Parzval-Net/Lienzo_Launcher/releases/download/v1.2.0/SHA256SUMS","size":100}]}"""

    @Test fun `assets del release`() {
        val a = UpdateRules.parseAssets(release())!!
        assertEquals(3, a.size)
        assertEquals("Lienzo-1.2.0.apk", UpdateRules.pickApk(a, "1.2.0")!!.name)
        assertNull(UpdateRules.pickApk(a, "1.3.0"))
        assertEquals("version.json", UpdateRules.pickVersionJson(a)!!.name)
        assertEquals(600000L, UpdateRules.pickApk(a, "1.2.0")!!.size)
    }

    @Test fun `borradores y prereleases se ignoran`() {
        assertNull(UpdateRules.parseAssets(release(draft = true)))
        assertNull(UpdateRules.parseAssets(release(pre = true)))
        assertNull(UpdateRules.parseAssets("{}"))
    }

    @Test fun `solo se confia en https de GitHub y del repo de Lienzo`() {
        val ok = "https://github.com/Parzval-Net/Lienzo_Launcher/releases/download/v1.2.0/Lienzo-1.2.0.apk"
        assertTrue(UpdateRules.isTrustedAssetUrl(ok))
        assertFalse(UpdateRules.isTrustedAssetUrl(ok.replace("https", "http")))
        assertFalse(UpdateRules.isTrustedAssetUrl("https://evil.example/Parzval-Net/Lienzo_Launcher/releases/download/v1/a.apk"))
        assertFalse(UpdateRules.isTrustedAssetUrl("https://github.com/otro/repo/releases/download/v1/a.apk"))
        assertFalse(UpdateRules.isTrustedAssetUrl("https://github.com.evil.example/Parzval-Net/Lienzo_Launcher/releases/download/v1/a.apk"))
        assertFalse(UpdateRules.isTrustedAssetUrl("https://user@github.com/Parzval-Net/Lienzo_Launcher/releases/download/v1/a.apk"))
        assertFalse(UpdateRules.isTrustedAssetUrl("no es url"))
        assertTrue(UpdateRules.isTrustedHost("objects.githubusercontent.com"))
        assertFalse(UpdateRules.isTrustedHost("githubusercontent.com.evil.example"))
        assertFalse(UpdateRules.isTrustedHost(null))
    }

    // ---- Espacio ----

    @Test fun `espacio suficiente`() {
        val mb = 1024L * 1024
        assertTrue(UpdateRules.hasSpace(1 * mb, 60 * mb))
        assertFalse(UpdateRules.hasSpace(1 * mb, 51 * mb))
        assertFalse(UpdateRules.hasSpace(0, 1000 * mb))
        assertFalse(UpdateRules.hasSpace(-1, 1000 * mb))
        assertFalse(UpdateRules.hasSpace(101 * mb, 10_000 * mb))
    }

    // ---- Hash ----

    @Test fun `sha256 conocido`() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", UpdateRules.sha256Hex("abc".toByteArray()))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", UpdateRules.sha256Hex(ByteArrayInputStream("abc".toByteArray())))
    }

    @Test fun `verificacion de hash`() {
        val h = UpdateRules.sha256Hex("abc".toByteArray())
        assertTrue(UpdateRules.hashMatches(h, h))
        assertTrue(UpdateRules.hashMatches(h.uppercase(), h))
        assertFalse(UpdateRules.hashMatches(h, UpdateRules.sha256Hex("abd".toByteArray())))
        assertFalse(UpdateRules.hashMatches("", ""))
        assertFalse(UpdateRules.hashMatches("abc", "abc"))
    }

    @Test fun `el hash por flujo cuenta los bytes`() {
        var n = 0
        UpdateRules.sha256Hex(ByteArrayInputStream(ByteArray(100_000)), { n += it })
        assertEquals(100_000, n)
    }

    // ---- Firma ----

    @Test fun `mismos certificados`() {
        val a = UpdateRules.fingerprints(listOf(byteArrayOf(1, 2, 3)))
        assertTrue(UpdateRules.sameSigners(a, UpdateRules.fingerprints(listOf(byteArrayOf(1, 2, 3)))))
        assertFalse(UpdateRules.sameSigners(a, UpdateRules.fingerprints(listOf(byteArrayOf(1, 2, 4)))))
        assertFalse(UpdateRules.sameSigners(a, UpdateRules.fingerprints(listOf(byteArrayOf(1, 2, 3), byteArrayOf(9)))))
        assertFalse(UpdateRules.sameSigners(emptySet(), emptySet()))
        assertFalse(UpdateRules.sameSigners(a, emptySet()))
    }

    // ---- Frecuencia ----

    private val t = UpdateRules.Timing.NORMAL
    private val H = 3_600_000L
    private val T0 = 1_000_000_000_000L

    @Test fun `la busqueda automatica espera 24 horas`() {
        val s = UpdateRules.State()
        assertTrue(UpdateRules.canCheck(s, T0, t, blocked = false, auto = true))
        val c = s.copy(lastCheckMs = T0)
        assertFalse(UpdateRules.canCheck(c, T0 + 23 * H, t, false, true))
        assertTrue(UpdateRules.canCheck(c, T0 + 24 * H, t, false, true))
        assertTrue(UpdateRules.canCheck(c, T0 - 5 * H, t, false, true)) // reloj atrasado
    }

    @Test fun `nunca busca con reproduccion, modo arte o desactivado`() {
        val s = UpdateRules.State()
        assertFalse(UpdateRules.canCheck(s, T0, t, blocked = true, auto = true))
        assertFalse(UpdateRules.canCheck(s, T0, t, blocked = false, auto = false))
    }

    @Test fun `una version disponible muestra el aviso`() {
        val s = UpdateRules.onCheck(UpdateRules.State(), T0, t, 4)
        assertEquals(T0, s.shownMs); assertEquals(T0, s.lastNoticeMs); assertEquals(4L, s.availableCode)
        assertTrue(UpdateRules.visible(s, T0 + H, t))
    }

    @Test fun `sin version disponible no hay aviso`() {
        val s = UpdateRules.onCheck(UpdateRules.State(), T0, t, 0)
        assertEquals(0L, s.shownMs)
        assertFalse(UpdateRules.visible(s, T0, t))
        assertEquals(T0, s.lastCheckMs)
    }

    @Test fun `el aviso se oculta solo a las 12 horas`() {
        val s = UpdateRules.onCheck(UpdateRules.State(), T0, t, 4)
        assertTrue(UpdateRules.visible(s, T0 + 12 * H - 1, t))
        assertFalse(UpdateRules.visible(s, T0 + 12 * H, t))
    }

    @Test fun `no vuelve antes de 24 horas desde que aparecio`() {
        val s = UpdateRules.onCheck(UpdateRules.State(), T0, t, 4)
        // Búsqueda manual a las 13 h: ya se ocultó solo y el enfriamiento no terminó
        val m = UpdateRules.onCheck(s, T0 + 13 * H, t, 4)
        assertEquals(0L, m.shownMs)
        // A las 24 h vuelve a avisar
        val a = UpdateRules.onCheck(m, T0 + 24 * H, t, 4)
        assertEquals(T0 + 24 * H, a.shownMs)
    }

    @Test fun `mas tarde silencia 24 horas`() {
        var s = UpdateRules.onCheck(UpdateRules.State(), T0, t, 4)
        s = UpdateRules.onDismissed(s, T0 + H, t.cooldownMs)
        assertFalse(UpdateRules.visible(s, T0 + 2 * H, t))
        // Nueva búsqueda dentro del silencio: no se muestra
        val n = UpdateRules.onCheck(s, T0 + 10 * H, t, 4)
        assertEquals(0L, n.shownMs)
        // Pasado el silencio y el enfriamiento, sí
        val later = UpdateRules.onCheck(n, T0 + 26 * H, t, 4)
        assertEquals(T0 + 26 * H, later.shownMs)
    }

    @Test fun `abrir el panel oculta el aviso`() {
        val s = UpdateRules.onOpened(UpdateRules.onCheck(UpdateRules.State(), T0, t, 4))
        assertEquals(0L, s.shownMs)
        assertFalse(UpdateRules.visible(s, T0, t))
    }

    @Test fun `si ya no hay version disponible el aviso desaparece`() {
        val s = UpdateRules.onCheck(UpdateRules.onCheck(UpdateRules.State(), T0, t, 4), T0 + 25 * H, t, 0)
        assertEquals(0L, s.shownMs); assertEquals(0L, s.availableCode)
    }

    @Test fun `tras instalar se borra el aviso`() {
        val s = UpdateRules.onCheck(UpdateRules.State(), T0, t, 4)
        assertEquals(0L, UpdateRules.afterInstalled(s, 4).availableCode)
        assertEquals(0L, UpdateRules.afterInstalled(s, 5).shownMs)
        assertEquals(4L, UpdateRules.afterInstalled(s, 3).availableCode)
        assertNotNull(UpdateRules.afterInstalled(UpdateRules.State(), 3))
    }
}
