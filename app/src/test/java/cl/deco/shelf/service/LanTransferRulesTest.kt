package cl.deco.shelf.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanTransferRulesTest {

    @Test fun validacionDeTokenEstricta() {
        assertTrue(LanTransferRules.validToken("a1b2c3d4", "a1b2c3d4"))
        assertFalse(LanTransferRules.validToken("a1b2c3d4", "a1b2c3d5"))
        assertFalse(LanTransferRules.validToken("a1b2c3d4", "a1b2c3d"))
        assertFalse(LanTransferRules.validToken("a1b2c3d4", null))
        assertFalse(LanTransferRules.validToken("", ""))
    }

    @Test fun parseQueryDecodificaParametros() {
        val q = LanTransferRules.parseQuery("t=abcd1234&name=Mi%20Foto.jpg&col=Warhammer%2040k")
        assertEquals("abcd1234", q["t"])
        assertEquals("Mi Foto.jpg", q["name"])
        assertEquals("Warhammer 40k", q["col"])
        assertTrue(LanTransferRules.parseQuery(null).isEmpty())
    }

    @Test fun saneaNombresYRechazaTraversal() {
        assertEquals("paisaje.jpg", LanTransferRules.sanitizeUploadName("../../etc/paisaje.jpg"))
        assertEquals("clip.mp4", LanTransferRules.sanitizeUploadName("C:\\temp\\clip.mp4"))
        assertEquals("a_b.png", LanTransferRules.sanitizeUploadName("a:b.png"))
        assertEquals("", LanTransferRules.sanitizeUploadName(".."))
    }

    @Test fun filtraExtensionesAdmitidas() {
        assertTrue(LanTransferRules.isAllowedMediaExt("foto.JPG"))
        assertTrue(LanTransferRules.isAllowedMediaExt("fondo.webp"))
        assertTrue(LanTransferRules.isAllowedMediaExt("video.mkv"))
        assertFalse(LanTransferRules.isAllowedMediaExt("malware.apk"))
        assertFalse(LanTransferRules.isAllowedMediaExt("script.sh"))
        assertFalse(LanTransferRules.isAllowedMediaExt("sin_extension"))
    }
}
