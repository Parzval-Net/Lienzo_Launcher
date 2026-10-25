package cl.deco.shelf.data.art

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionsTest {

    @Test fun prefijoLienzoSeQuita() {
        assertEquals("Warhammer 40k", Collections.fromFolder("Lienzo-Warhammer 40k"))
        assertEquals("warhammer40k", Collections.fromFolder("lienzo_warhammer40k"))
        assertEquals("Monet", Collections.fromFolder("LIENZO Monet"))
    }

    @Test fun otraCarpetaConservaSuNombre() {
        assertEquals("Camera", Collections.fromFolder("Camera"))
        assertEquals("Vacaciones 2026", Collections.fromFolder("Vacaciones_2026"))
    }

    @Test fun sinCarpetaEsGeneral() {
        assertEquals(Collections.GENERAL, Collections.fromFolder(null))
        assertEquals(Collections.GENERAL, Collections.fromFolder("  "))
        assertEquals(Collections.GENERAL, Collections.fromFolder("Lienzo-"))
    }

    @Test fun carpetaDeVideosSegura() {
        assertEquals("", Collections.dirName(Collections.GENERAL))
        assertEquals("Warhammer 40k", Collections.dirName("Warhammer 40k"))
        assertEquals("a_b_c", Collections.dirName("a/b\\c"))
    }

    @Test fun generalPrimeroYLuegoAlfabetico() {
        assertEquals(listOf("General", "Monet", "warhammer"), Collections.sorted(listOf("warhammer", "General", "Monet", "Monet")))
    }

    @Test fun todasIncluyeCualquierColeccion() {
        assertTrue(Collections.matches(Collections.ALL, "Monet"))
        assertTrue(Collections.matches("Monet", "Monet"))
        assertFalse(Collections.matches("Monet", "General"))
    }
}
