package cl.deco.shelf.shelf

import cl.deco.shelf.shelf.ContinueSelector.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinueSelectorTest {
    private fun cand(pkg: String, title: String, eng: Long?, type: Int? = 0, seq: Int = 0) =
        Candidate(pkg, TopShelfItem(title, null, null, null), eng, type, seq)

    private fun titles(l: List<Candidate>) = l.map { it.item.title }

    @Test fun ordenaPorEngagementMasRecientePrimero() {
        val r = ContinueSelector.select(listOf(cand("a", "viejo", 100, seq = 0), cand("b", "nuevo", 300, seq = 1), cand("c", "medio", 200, seq = 2)))
        assertEquals(listOf("nuevo", "medio", "viejo"), titles(r))
    }

    @Test fun sinEngagementVaDespuesDeLosQueSiTienenYNoSeDescarta() {
        val r = ContinueSelector.select(listOf(cand("a", "sin", null, seq = 0), cand("b", "cero", 0, seq = 1), cand("c", "con", 50, seq = 2)))
        assertEquals(listOf("con", "sin", "cero"), titles(r))
    }

    @Test fun sinEngagementConservaElOrdenDeLectura() {
        val r = ContinueSelector.select(listOf(cand("a", "uno", null, seq = 0), cand("a", "dos", null, seq = 1), cand("a", "tres", null, seq = 2)))
        assertEquals(listOf("uno", "dos", "tres"), titles(r))
    }

    @Test fun continueYNextAntesQueNewYWatchlistAunqueSeanMasAntiguos() {
        val r = ContinueSelector.select(
            listOf(
                cand("a", "nuevo", 900, ContinueSelector.TYPE_NEW, 0),
                cand("b", "lista", 800, ContinueSelector.TYPE_WATCHLIST, 1),
                cand("c", "siguiente", 100, ContinueSelector.TYPE_NEXT, 2),
                cand("d", "continuar", 50, ContinueSelector.TYPE_CONTINUE, 3),
            ),
        )
        assertEquals(listOf("siguiente", "continuar", "nuevo", "lista"), titles(r))
    }

    @Test fun programasDeCanalSinEngagementVanDespuesDeWatchNextConEngagement() {
        val wn = cand("hbo", "wn", 10, ContinueSelector.TYPE_NEW, 0)
        val canal = cand("nf", "canal", null, null, 1)
        assertEquals(listOf("wn", "canal"), titles(ContinueSelector.select(listOf(canal, wn))))
    }

    @Test fun deduplicaPorPaqueteYTituloNormalizado() {
        val r = ContinueSelector.select(listOf(cand("a", "El Show!", 200, seq = 0), cand("a", "el  show", 100, seq = 1), cand("b", "El Show", 50, seq = 2)))
        assertEquals(2, r.size)
        assertEquals(listOf("a", "b"), r.map { it.pkg })
        assertEquals(200L, r[0].engagement)
    }

    @Test fun dedupConservaElDeMayorPrioridadEntreWatchNextYCanal() {
        val wn = cand("nf", "Serie", 10, 0, 0)
        val canal = cand("nf", "serie", null, null, 1)
        val r = ContinueSelector.select(listOf(canal, wn))
        assertEquals(1, r.size)
        assertEquals(10L, r[0].engagement)
    }

    @Test fun respetaElTopeDeDoce() {
        val l = (0 until 30).map { cand("p${it % 10}", "t$it", 1000L - it, seq = it) }
        assertEquals(12, ContinueSelector.select(l).size)
        assertEquals(5, ContinueSelector.select(l, max = 5).size)
    }

    @Test fun unaAppNoTapaALasDemasPeroSeCompletaSiSobraEspacio() {
        val yt = (0 until 10).map { cand("yt", "v$it", 1000L - it, seq = it) }
        val amazon = listOf(cand("amz", "peli", 10, seq = 10))
        val r = ContinueSelector.select(yt + amazon)
        assertTrue("amz debe entrar", r.any { it.pkg == "amz" })
        assertEquals(11, r.size)
        assertEquals(10, r.count { it.pkg == "yt" })
        // con mucho contenido de yt, el cupo blando limita a 4 + lo que sobre
        val muchos = (0 until 20).map { cand("yt", "w$it", 5000L - it, seq = it) }
        val r2 = ContinueSelector.select(muchos + amazon)
        assertEquals(12, r2.size)
        assertEquals(11, r2.count { it.pkg == "yt" })
        assertTrue(r2.any { it.pkg == "amz" })
    }

    @Test fun elResultadoConservaElOrdenGlobalAunqueElCupoReordeneLaSeleccion() {
        val l = (0 until 6).map { cand("yt", "y$it", 1000L - it, seq = it) } + cand("amz", "a", 1L, seq = 6)
        val r = ContinueSelector.select(l, max = 5)
        assertEquals(listOf("y0", "y1", "y2", "y3", "a"), titles(r))
    }

    @Test fun noDescartaItemsSinImagenNiIntent() {
        val c = Candidate("a", TopShelfItem("solo titulo", null, null, null), null, null, 0)
        assertEquals(1, ContinueSelector.select(listOf(c)).size)
    }

    @Test fun listaVaciaDaListaVacia() {
        assertTrue(ContinueSelector.select(emptyList()).isEmpty())
    }

    @Test fun reconoceCanalesSeguirViendoEnVariosIdiomas() {
        for (n in listOf(
            "Continuar viendo", "SEGUIR VIENDO", "Seguir viendo", "Continue Watching", "Keep watching", "Keep Watching",
            "Continue a ver", "Continuar assistindo", "Continuer à regarder", "Weiterschauen", "Continua a guardare",
            "Retomar viendo", "Resume watching", "Continúa viendo", "Continue watching on Netflix",
        )) {
            assertTrue(n, ContinueSelector.isContinueChannel(n))
        }
    }

    @Test fun noConfundeCanalesDeRecomendaciones() {
        for (n in listOf("Netflix", "Recomendaciones para ti", "Mi lista", "Destacados", "Prime Video", "Recomendados", "Suscripciones", "Tendencias", "Seguir a tus creadores", "Continuidad", "")) {
            assertFalse(n, ContinueSelector.isContinueChannel(n))
        }
        assertFalse(ContinueSelector.isContinueChannel(null))
    }
}
