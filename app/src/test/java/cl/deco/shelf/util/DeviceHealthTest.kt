package cl.deco.shelf.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceHealthTest {
    private val day = 86_400_000L
    @Test fun cmaBajaRecomiendaReiniciar() = assertTrue(DeviceHealth.Snapshot(40 * 1024, 544 * 1024, 3 * day).restartRecommended)
    @Test fun cmaSanaYPocoUptimeNoAvisa() = assertFalse(DeviceHealth.Snapshot(347 * 1024, 544 * 1024, day).restartRecommended)
    @Test fun seisDiasEncendidoAvisa() = assertTrue(DeviceHealth.Snapshot(347 * 1024, 544 * 1024, 6 * day).restartRecommended)
    @Test fun sinDatosCmaNoAvisa() = assertFalse(DeviceHealth.Snapshot(-1, -1, day).restartRecommended)
    @Test fun textoUptime() = assertEquals("1 d 19 h", DeviceHealth.uptimeText(43 * 3_600_000L))
}

class DeviceHealthRecoTest {
    private val day = 86_400_000L
    private fun snap(cmaMb: Long, up: Long) = DeviceHealth.Snapshot(cmaMb * 1024, 544 * 1024, up)
    private val ram = 2000L * 1024
    private fun reco(s: DeviceHealth.Snapshot, availMb: Long = 700, freed: Boolean = false) =
        DeviceHealth.recommend(s, availMb * 1024, ram, freed).action

    @Test fun todoSanoNoRecomienda() = assertEquals(DeviceHealth.Action.NONE, reco(snap(347, day)))
    @Test fun cmaBajaPrimeroLiberar() = assertEquals(DeviceHealth.Action.FREE_MEMORY, reco(snap(40, 3 * day)))
    @Test fun cmaBajaTrasLiberarReiniciar() = assertEquals(DeviceHealth.Action.RESTART, reco(snap(40, 3 * day), freed = true))
    @Test fun cmaBajaYUptimeReiniciar() = assertEquals(DeviceHealth.Action.RESTART, reco(snap(40, 6 * day)))
    @Test fun soloUptimeReiniciar() = assertEquals(DeviceHealth.Action.RESTART, reco(snap(347, 6 * day)))
    @Test fun ramBajaLiberar() = assertEquals(DeviceHealth.Action.FREE_MEMORY, reco(snap(347, day), availMb = 200))
    @Test fun ramBajaSinTotalNoRecomienda() =
        assertEquals(DeviceHealth.Action.NONE, DeviceHealth.recommend(snap(347, day), 100, 0).action)
    @Test fun umbralCmaExactoNoEsBajo() = assertEquals(DeviceHealth.Action.NONE, reco(snap(50, 3 * day)))

    // Calibración (ver DeviceHealth.CMA_LOW_KB): con 18 h encendido CmaFree osciló 50-196 MB sin fallas; el criterio es el uptime
    @Test fun lecturaNormalDeCalibracionNoAvisa() = assertEquals(DeviceHealth.Action.NONE, reco(snap(77, day)))
    @Test fun cmaMuyBajaRecienReiniciadoNoAvisa() = assertEquals(DeviceHealth.Action.NONE, reco(snap(40, day)))
    @Test fun recienReiniciadoNoAvisa() = assertEquals(DeviceHealth.Action.NONE, reco(snap(347, day)))
    @Test fun umbralVigenteEs50Mb() = assertEquals(50L * 1024, DeviceHealth.CMA_LOW_KB)
    @Test fun cincoDiasEncendidoAvisa() = assertEquals(DeviceHealth.Action.RESTART, reco(snap(347, 5 * day + 3_600_000L)))
    @Test fun cuatroDiasEncendidoNoAvisa() = assertEquals(DeviceHealth.Action.NONE, reco(snap(347, 4 * day)))
    @Test fun cmaBajaSinUptimeSeConfirma() = assertTrue(snap(40, 3 * day).needsConfirm)
    @Test fun cmaBajaConUptimeNoEsperaConfirmacion() = assertFalse(snap(40, 6 * day).needsConfirm)
    @Test fun cmaSanaNoSeConfirma() = assertFalse(snap(347, day).needsConfirm)

    @Test fun veredictoSinMejora() = assertTrue(DeviceHealth.freeVerdict(600 * 1024, 603 * 1024, 160 * 1024, 158 * 1024).startsWith("Casi no cambió"))
    @Test fun veredictoConMejora() = assertTrue(DeviceHealth.freeVerdict(600 * 1024, 640 * 1024, 160 * 1024, 158 * 1024).startsWith("Se liberaron 40 MB"))

    @Test fun snoozeVigente() = assertTrue(DeviceHealth.snoozed(1_000, 1_000 + 3_600_000))
    @Test fun snoozeVencido() = assertFalse(DeviceHealth.snoozed(5_000, 4_000))
    @Test fun snoozeConRelojAdelantadoSeIgnora() = assertFalse(DeviceHealth.snoozed(0, 3 * day)) // más de 24 h: dato inválido
}
