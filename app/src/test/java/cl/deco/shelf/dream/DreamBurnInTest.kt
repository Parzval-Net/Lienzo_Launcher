package cl.deco.shelf.dream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DreamBurnInTest {

    @Test fun desplazamientoCambiaEntreMinutosYAlternaEsquinaCada15() {
        val p0 = DreamBurnIn.positionForMinute(0)
        val p1 = DreamBurnIn.positionForMinute(1)
        val p14 = DreamBurnIn.positionForMinute(14)
        val p15 = DreamBurnIn.positionForMinute(15)
        val p30 = DreamBurnIn.positionForMinute(30)

        assertFalse(p0.onRight)
        assertFalse(p14.onRight)
        assertTrue(p15.onRight)
        assertFalse(p30.onRight)

        assertNotEquals(p0.shiftXdp to p0.shiftYdp, p1.shiftXdp to p1.shiftYdp)
        assertEquals(0, p0.shiftXdp)
        assertEquals(0, p0.shiftYdp)
    }

    @Test fun desplazamientosDentroDeRangoSeguro() {
        for (m in 0L..60L) {
            val p = DreamBurnIn.positionForMinute(m)
            assertTrue(p.shiftXdp in -12..12)
            assertTrue(p.shiftYdp in -8..8)
        }
    }
}
