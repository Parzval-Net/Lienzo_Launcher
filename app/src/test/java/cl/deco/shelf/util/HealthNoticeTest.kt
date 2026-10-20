package cl.deco.shelf.util

import cl.deco.shelf.util.HealthNotice.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthNoticeTest {
    private val t = HealthNotice.Timing.NORMAL
    private val h = 3_600_000L
    private val day = 24 * h
    private val total = 544L * 1024
    private val low = 97L * 1024          // falla real observada
    private val ok = 347L * 1024          // recién reiniciado
    private fun eval(s: State, now: Long, cma: Long, up: Long = 20 * h) = HealthNotice.onEvaluation(s, now, cma, total, up, t)

    @Test fun primeraEvaluacionBajaNoMuestra() {
        val s = eval(State(), 100 * h, low)
        assertEquals(1, s.lowCount); assertFalse(HealthNotice.visible(s, 100 * h, t))
    }

    @Test fun dosEvaluacionesBajasSeparadasMuestran() {
        var s = eval(State(), 100 * h, low)
        s = eval(s, 106 * h, low)
        assertEquals(2, s.lowCount); assertTrue(HealthNotice.visible(s, 106 * h, t))
        assertEquals(106 * h, s.lastNoticeMs)
    }

    @Test fun dosLecturasMuyJuntasCuentanComoUna() {
        var s = eval(State(), 100 * h, low)
        s = eval(s, 100 * h + 10 * 60_000L, low) // menos de 30 min
        assertEquals(1, s.lowCount); assertFalse(HealthNotice.visible(s, 101 * h, t))
    }

    @Test fun maximoUnaEvaluacionCada6Horas() {
        val s = State(lastEvalMs = 100 * h)
        assertFalse(HealthNotice.canEvaluate(s, 105 * h, t, blocked = false))
        assertTrue(HealthNotice.canEvaluate(s, 106 * h, t, blocked = false))
        assertTrue(HealthNotice.canEvaluate(State(), 1, t, blocked = false))
    }

    @Test fun conReproduccionOModoArteNoSeEvalua() = assertFalse(HealthNotice.canEvaluate(State(), 100 * h, t, blocked = true))

    @Test fun lecturaSanaEnElMedioReiniciaElContador() {
        var s = eval(State(), 100 * h, low)
        s = eval(s, 106 * h, ok)
        assertEquals(0, s.lowCount)
        s = eval(s, 112 * h, low)
        assertEquals(1, s.lowCount) // "consecutivas": parte de cero
    }

    @Test fun histeresisSobreElUmbral() {
        val inBand = DeviceHealth.CMA_LOW_KB + 10 * 1024   // sobre el umbral pero bajo umbral + 30 MB
        var s = eval(eval(State(), 100 * h, low), 106 * h, low) // chip visible
        s = eval(s, 112 * h, inBand)
        assertTrue("en la banda no se normaliza", HealthNotice.visible(s, 112 * h, t))
        s = eval(s, 118 * h, DeviceHealth.CMA_LOW_KB + HealthNotice.HYSTERESIS_KB)
        assertFalse("sobre umbral + 30 MB el chip desaparece", HealthNotice.visible(s, 118 * h, t))
        assertEquals(0, s.lowCount)
    }

    @Test fun ignoradoSeOcultaA12HorasYNoVuelveAntesDe24() {
        var s = eval(eval(State(), 100 * h, low), 106 * h, low) // aparece a las 106 h
        assertTrue(HealthNotice.visible(s, 117 * h, t))
        assertFalse(HealthNotice.visible(s, 118 * h, t))        // 12 h después se oculta solo
        s = eval(s, 118 * h, low)                               // nueva evaluación baja: sigue en enfriamiento
        assertFalse(HealthNotice.visible(s, 118 * h, t))
        s = eval(s, 129 * h, low)                               // 23 h después del aviso
        assertFalse(HealthNotice.visible(s, 129 * h, t))
        s = eval(s, 130 * h, low)                               // 24 h después
        assertTrue(HealthNotice.visible(s, 130 * h, t))
    }

    @Test fun descartarOcultaYNoVuelveAntesDe24Horas() {
        var s = eval(eval(State(), 100 * h, low), 106 * h, low)
        s = HealthNotice.onDismissed(s, 107 * h, day)
        assertFalse(HealthNotice.visible(s, 107 * h, t))
        s = eval(s, 120 * h, low)
        assertFalse(HealthNotice.visible(s, 120 * h, t))        // dentro de las 24 h del descarte
        s = eval(s, 132 * h, low)
        assertTrue(HealthNotice.visible(s, 132 * h, t))         // pasaron más de 24 h del aviso y del descarte
    }

    @Test fun abrirElPanelOcultaElChip() {
        val s = HealthNotice.onOpened(eval(eval(State(), 100 * h, low), 106 * h, low))
        assertFalse(HealthNotice.visible(s, 106 * h + 1, t))
        assertEquals(106 * h, s.lastNoticeMs) // sigue el enfriamiento
    }

    @Test fun reinicioOcultaYReiniciaElContador() {
        val s = eval(eval(State(lastUptimeMs = 40 * h), 100 * h, low, up = 40 * h), 106 * h, low, up = 46 * h)
        assertTrue(HealthNotice.rebooted(s, 2 * h))
        val r = HealthNotice.afterReboot(s, 2 * h)
        assertEquals(0, r.lowCount); assertFalse(HealthNotice.visible(r, 107 * h, t)); assertEquals(0L, r.lastEvalMs)
        assertEquals(s.lastNoticeMs, r.lastNoticeMs)
        assertFalse(HealthNotice.rebooted(r, 3 * h))
    }

    @Test fun uptimeDe7DiasUnAvisoPorSemana() {
        val up = 8 * day
        var s = eval(State(), 200 * h, ok, up)                   // una sola evaluación basta por uptime
        assertTrue(HealthNotice.visible(s, 200 * h, t)); assertEquals(200 * h, s.lastUptimeNoticeMs)
        s = eval(s, 213 * h, ok, up + 13 * h)                    // se ocultó por tiempo
        assertFalse(HealthNotice.visible(s, 213 * h, t))
        s = eval(s, 250 * h, ok, up + 50 * h)                    // pasó el enfriamiento de 24 h pero no la semana
        assertFalse(HealthNotice.visible(s, 250 * h, t))
        s = eval(s, 200 * h + 7 * day, ok, up + 7 * day)
        assertTrue(HealthNotice.visible(s, 200 * h + 7 * day, t))
    }

    @Test fun cmaSinDatosNoAvisa() {
        val s = HealthNotice.onEvaluation(State(), 100 * h, -1, -1, 20 * h, t)
        assertEquals(0, s.lowCount); assertFalse(HealthNotice.visible(s, 100 * h, t))
    }

    @Test fun modoRapidoEsUn360Avo() {
        assertEquals(60_000L, HealthNotice.Timing.FAST.evalGapMs)
        assertEquals(240_000L, HealthNotice.Timing.FAST.cooldownMs)
    }
}
