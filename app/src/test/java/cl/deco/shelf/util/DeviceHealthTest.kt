package cl.deco.shelf.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceHealthTest {
    private val day = 86_400_000L
    @Test fun cmaBajaRecomiendaReiniciar() = assertTrue(DeviceHealth.Snapshot(97 * 1024, 544 * 1024, day).restartRecommended)
    @Test fun cmaSanaYPocoUptimeNoAvisa() = assertFalse(DeviceHealth.Snapshot(347 * 1024, 544 * 1024, day).restartRecommended)
    @Test fun ochoDiasEncendidoAvisa() = assertTrue(DeviceHealth.Snapshot(347 * 1024, 544 * 1024, 8 * day).restartRecommended)
    @Test fun sinDatosCmaNoAvisa() = assertFalse(DeviceHealth.Snapshot(-1, -1, day).restartRecommended)
    @Test fun textoUptime() = assertEquals("1 d 19 h", DeviceHealth.uptimeText(43 * 3_600_000L))
}
