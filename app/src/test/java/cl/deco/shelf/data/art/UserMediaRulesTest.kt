package cl.deco.shelf.data.art

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserMediaRulesTest {

    private fun reason(v: VideoRules.Verdict) = (v as? VideoRules.Verdict.Rejected)?.reason

    // ---- Validación de video ----

    @Test fun acepta720pH264() {
        assertEquals(VideoRules.Verdict.Ok, VideoRules.evaluate("video/avc", 1280, 720, 0, 30f))
    }

    @Test fun acepta1080pHevcA60fps() {
        assertEquals(VideoRules.Verdict.Ok, VideoRules.evaluate("video/hevc", 1920, 1080, 0, 60f))
        assertEquals(VideoRules.Verdict.Ok, VideoRules.evaluate("video/hevc", 1920, 1080, 0, 59.94f))
    }

    @Test fun rechaza4k() {
        val v = VideoRules.evaluate("video/avc", 3840, 2160, 0, 30f)
        assertEquals(VideoRules.Reason.RESOLUTION, reason(v))
        assertTrue((v as VideoRules.Verdict.Rejected).message.contains("4K u otros formatos pueden afectar la reproducción de Netflix"))
    }

    @Test fun rechazaCodecDistintoDeAvcHevc() {
        assertEquals(VideoRules.Reason.CODEC, reason(VideoRules.evaluate("video/x-vnd.on2.vp9", 1280, 720, 0, 30f)))
        assertEquals(VideoRules.Reason.CODEC, reason(VideoRules.evaluate("video/av01", 1280, 720, 0, 30f)))
    }

    @Test fun rechazaMasDe60fps() {
        assertEquals(VideoRules.Reason.FPS, reason(VideoRules.evaluate("video/avc", 1920, 1080, 0, 120f)))
        assertEquals(VideoRules.Reason.FPS, reason(VideoRules.evaluate("video/avc", 1280, 720, 0, 61f)))
    }

    @Test fun fpsDesconocidoSeAcepta() {
        assertEquals(VideoRules.Verdict.Ok, VideoRules.evaluate("video/avc", 1280, 720, 0, 0f))
    }

    @Test fun verticalSeRechazaYLaRotacionIntercambiaEjes() {
        // 1080x1920 sin rotación es vertical: excede el alto
        assertEquals(VideoRules.Reason.RESOLUTION, reason(VideoRules.evaluate("video/avc", 1080, 1920, 0, 30f)))
        // 1920x1080 con rotación 90 queda vertical
        assertEquals(VideoRules.Reason.RESOLUTION, reason(VideoRules.evaluate("video/avc", 1920, 1080, 90, 30f)))
        // 1080x1920 almacenado con rotación 90 se reproduce en horizontal 1920x1080
        assertEquals(VideoRules.Verdict.Ok, VideoRules.evaluate("video/avc", 1080, 1920, 90, 30f))
    }

    @Test fun rechazaIlegiblesHdrYSinDecodificador() {
        assertEquals(VideoRules.Reason.UNREADABLE, reason(VideoRules.evaluate(null, 1280, 720)))
        assertEquals(VideoRules.Reason.UNREADABLE, reason(VideoRules.evaluate("video/avc", 0, 0)))
        assertEquals(VideoRules.Reason.HDR, reason(VideoRules.evaluate("video/hevc", 1920, 1080, 0, 30f, hdr = true)))
        assertEquals(VideoRules.Reason.NO_DECODER, reason(VideoRules.evaluate("video/hevc", 1920, 1080, 0, 30f, decoderAvailable = false)))
    }

    // ---- Espacio ----

    private val mb = 1024L * 1024

    @Test fun espacioDejaAlMenos500MbLibres() {
        assertTrue(SpaceRules.check(100 * mb, 700 * mb).ok)            // 100 + 500 <= 700
        assertFalse(SpaceRules.check(201 * mb, 700 * mb).ok)           // 201 + 500 > 700
        val c = SpaceRules.check(300 * mb, 700 * mb)
        assertFalse(c.ok)
        assertEquals(100 * mb, c.shortBy)
    }

    @Test fun espacioExactoEnElLimiteEsValido() {
        assertTrue(SpaceRules.check(200 * mb, 700 * mb).ok)
        assertEquals(0L, SpaceRules.check(200 * mb, 700 * mb).shortBy)
    }

    @Test fun espacioConDiscoLlenoYNegativos() {
        assertFalse(SpaceRules.check(1, 0).ok)
        assertEquals(0L, SpaceRules.check(-5, 600 * mb).needed)
        assertTrue(SpaceRules.check(0, 500 * mb).ok)
    }

    @Test fun estimacionYFormatoDeTamano() {
        assertEquals(2 * SpaceRules.EST_IMAGE_BYTES + 10 * mb, SpaceRules.estimate(2, 10 * mb))
        assertEquals("1 KB", SpaceRules.format(1))
        assertEquals("1,5 MB", SpaceRules.format((1.5 * mb).toLong()))
        assertEquals("1,00 GB", SpaceRules.format(1024 * mb))
    }

    // ---- Fuente del fondo ----

    @Test fun curadaSoloConsultaLaColeccion() {
        assertEquals(listOf(Src.CURATED), SourcePolicy.order(SourcePolicy.CURATED, 0, true))
    }

    @Test fun misFondosVacioCaeALaCurada() {
        assertEquals(listOf(Src.CURATED), SourcePolicy.order(SourcePolicy.MINE, 0, false))
        assertEquals(listOf(Src.CURATED), SourcePolicy.order(SourcePolicy.BOTH, 3, false))
    }

    @Test fun misFondosConObrasEsPrimeroConRespaldo() {
        assertEquals(listOf(Src.MINE, Src.CURATED), SourcePolicy.order(SourcePolicy.MINE, 7, true))
    }

    @Test fun ambasAlternaYAguantaTickNegativo() {
        assertEquals(Src.CURATED, SourcePolicy.order(SourcePolicy.BOTH, 0, true).first())
        assertEquals(Src.MINE, SourcePolicy.order(SourcePolicy.BOTH, 1, true).first())
        assertEquals(Src.MINE, SourcePolicy.order(SourcePolicy.BOTH, -1, true).first())
    }

    // ---- Video del protector y modo arte ----

    @Test fun monetPorDefecto() {
        assertEquals("monet", VideoPolicy.pick("monet", listOf("a.mp4"), true, 0))
        assertNull(VideoPolicy.pick("monet", listOf("a.mp4"), false, 0))
        assertEquals("monet", VideoPolicy.pick("valor-desconocido", emptyList(), true, 0))
    }

    @Test fun videoPropioElegidoOCaeAMonet() {
        assertEquals("a.mp4", VideoPolicy.pick(VideoPolicy.userChoice("a.mp4"), listOf("a.mp4", "b.mp4"), true, 5))
        assertEquals("monet", VideoPolicy.pick(VideoPolicy.userChoice("borrado.mp4"), listOf("b.mp4"), true, 0))
        assertNull(VideoPolicy.pick(VideoPolicy.userChoice("borrado.mp4"), emptyList(), false, 0))
    }

    @Test fun alternarTodosRotaMonetYPropios() {
        val u = listOf("a.mp4", "b.mp4")
        assertEquals(listOf("monet", "a.mp4", "b.mp4", "monet"), (0..3).map { VideoPolicy.pick("all", u, true, it) })
        assertEquals(listOf("a.mp4", "b.mp4", "a.mp4"), (0..2).map { VideoPolicy.pick("all", u, false, it) })
        assertNull(VideoPolicy.pick("all", emptyList(), false, 0))
    }

    // ---- Geometría ----

    @Test fun recorteCoverCentradoEnImagenAncha() {
        val r = ImportMath.coverRect(4000, 1000)
        assertEquals(1778, r.width)       // 1000 * 16/9
        assertEquals(1000, r.height)
        assertEquals((4000 - r.width) / 2, r.left)
    }

    @Test fun recorteCoverCentradoEnImagenAlta() {
        val r = ImportMath.coverRect(1920, 2400)
        assertEquals(1920, r.width)
        assertEquals(1080, r.height)
        assertEquals((2400 - 1080) / 2, r.top)
    }

    @Test fun recorteCoverDe16x9EsLaImagenCompleta() {
        val r = ImportMath.coverRect(3840, 2160)
        assertEquals(PixelRect(0, 0, 3840, 2160), r)
    }

    @Test fun sampleSizeMantieneAlMenos1920x1080() {
        assertEquals(1, ImportMath.sampleSize(1920, 1080))
        assertEquals(2, ImportMath.sampleSize(4000, 3000))
        assertEquals(1, ImportMath.sampleSize(3000, 1500)) // al partir queda 1500 < 1920
        assertEquals(4, ImportMath.sampleSize(8000, 6000))
    }

    @Test fun verticalesYCuadradasUsanPasepartout() {
        assertTrue(ImportMath.needsMat(1080, 1920))
        assertTrue(ImportMath.needsMat(1000, 1000))
        assertFalse(ImportMath.needsMat(1920, 1080))
    }

    @Test fun rotacionExif() {
        assertEquals(0, ImportMath.exifRotation(1))
        assertEquals(90, ImportMath.exifRotation(6))
        assertEquals(180, ImportMath.exifRotation(3))
        assertEquals(270, ImportMath.exifRotation(8))
    }

    @Test fun nombresSegurosYUnicos() {
        assertEquals("video.mp4", ImportMath.safeFileName("video.MP4", emptySet()))
        assertEquals("video-2.mp4", ImportMath.safeFileName("video.mp4", setOf("Video.mp4")))
        assertEquals("video-3.mp4", ImportMath.safeFileName("video.mp4", setOf("video.mp4", "video-2.mp4")))
        assertEquals("a_b_c.mp4", ImportMath.safeFileName("a/b\\c.mp4", emptySet()))
        assertEquals("video", ImportMath.safeFileName("...", emptySet()))
    }
}
