package cl.deco.shelf.data.art

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtCatalogTest {

    private val sample = """{"v":1,"items":[
        {"id":"a","title":"Nenúfares","artist":"Claude Monet","year":"1906","museum":"AIC","license":"CC0","mode":"crop",
         "url":"https://upload.wikimedia.org/x/2560px-a.jpg","crop":[0.1,0.25,0.5,0.28125],"src":"File:A.jpg"},
        {"id":"b","title":"Retrato","artist":"X","year":"1500","museum":"M","license":"PD","mode":"mat",
         "url":"https://upload.wikimedia.org/x/1280px-b.jpg","crop":[0.02,0.03,0.9,0.94],"src":"File:B.jpg","mat":true,"home":false}]}"""

    @Test fun parseaMetadatosYBanderas() {
        val l = ArtCatalog.parse(sample)
        assertEquals(2, l.size)
        assertEquals("Nenúfares", l[0].title)
        assertTrue(l[0].home)
        assertFalse(l[0].mat)
        assertEquals("a.jpg", l[0].file)
        assertEquals("https://commons.wikimedia.org/wiki/File:A.jpg", l[0].sourceUrl)
        assertTrue(l[1].mat)
        assertFalse(l[1].home)
        assertEquals("mat", l[1].mode)
        assertEquals(listOf(0.02, 0.03, 0.9, 0.94), l[1].crop)
    }

    @Test fun cropRectDesdeNormalizado() {
        val r = ArtCatalog.cropRect(listOf(0.1, 0.25, 0.5, 0.28125), 2560, 1600)
        assertEquals(PixelRect(256, 400, 1536, 850), r)
        assertEquals(1280, r.width)
        assertEquals(450, r.height)
    }

    @Test fun cropRectSeAcotaALosLimites() {
        val r = ArtCatalog.cropRect(listOf(0.9, 0.9, 0.5, 0.5), 1000, 500)
        assertEquals(PixelRect(900, 450, 1000, 500), r)
        val full = ArtCatalog.cropRect(listOf(0.0, 0.0, 1.0, 1.0), 1920, 1080)
        assertEquals(PixelRect(0, 0, 1920, 1080), full)
    }

    @Test fun sampleSizeMantieneAnchoObjetivo() {
        assertEquals(1, ArtCatalog.sampleSize(2560, 1920))
        assertEquals(2, ArtCatalog.sampleSize(3840, 1920))
        assertEquals(4, ArtCatalog.sampleSize(8000, 1920))
        assertEquals(1, ArtCatalog.sampleSize(1500, 1920))
    }

    @Test fun matCabeEnElPassePartout() {
        val (w, h) = ArtCatalog.matArtSize(1000, 2000)  // vertical: manda el alto (864)
        assertEquals(864, h)
        assertEquals(432, w)
        val (w2, h2) = ArtCatalog.matArtSize(3000, 1000) // apaisada: manda el ancho (1497)
        assertEquals(1497, w2)
        assertEquals(499, h2)
    }

    @Test fun assetRealTienePesoYObrasValidas() {
        val f = File("src/main/assets/art_index.json")
        // 117 obras (60 originales + 57 CC0 de The Met y la NGA vía Commons): el índice sigue siendo texto liviano en el APK
        assertTrue("art_index.json < 80 KB", f.length() < 80 * 1024)
        val l = ArtCatalog.parse(f.readText())
        assertEquals(117, l.size)
        assertEquals(117, l.map { it.id }.toSet().size)
        l.forEach {
            assertTrue(it.url, it.url.startsWith("https://upload.wikimedia.org/") && Regex("\\.jpe?g", RegexOption.IGNORE_CASE).containsMatchIn(it.url))
            assertTrue(it.id, it.crop[0] >= 0 && it.crop[1] >= 0 && it.crop[0] + it.crop[2] <= 1.0001 && it.crop[1] + it.crop[3] <= 1.0001)
            assertEquals(it.id, it.mode == "mat", it.mat)
        }
    }
}
