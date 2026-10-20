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

class DeviceHealthRecoTest {
    private val day = 86_400_000L
    private fun snap(cmaMb: Long, up: Long) = DeviceHealth.Snapshot(cmaMb * 1024, 544 * 1024, up)
    private val ram = 2000L * 1024
    private fun reco(s: DeviceHealth.Snapshot, availMb: Long = 700, freed: Boolean = false) =
        DeviceHealth.recommend(s, availMb * 1024, ram, freed).action

    @Test fun todoSanoNoRecomienda() = assertEquals(DeviceHealth.Action.NONE, reco(snap(347, day)))
    @Test fun cmaBajaPrimeroLiberar() = assertEquals(DeviceHealth.Action.FREE_MEMORY, reco(snap(97, day)))
    @Test fun cmaBajaTrasLiberarReiniciar() = assertEquals(DeviceHealth.Action.RESTART, reco(snap(97, day), freed = true))
    @Test fun cmaBajaYUptimeReiniciar() = assertEquals(DeviceHealth.Action.RESTART, reco(snap(97, 8 * day)))
    @Test fun soloUptimeReiniciar() = assertEquals(DeviceHealth.Action.RESTART, reco(snap(347, 8 * day)))
    @Test fun ramBajaLiberar() = assertEquals(DeviceHealth.Action.FREE_MEMORY, reco(snap(347, day), availMb = 200))
    @Test fun ramBajaSinTotalNoRecomienda() =
        assertEquals(DeviceHealth.Action.NONE, DeviceHealth.recommend(snap(347, day), 100, 0).action)
    @Test fun umbralCmaExactoNoEsBajo() = assertEquals(DeviceHealth.Action.NONE, reco(snap(150, day)))

    @Test fun veredictoSinMejora() = assertTrue(DeviceHealth.freeVerdict(600 * 1024, 603 * 1024, 160 * 1024, 158 * 1024).startsWith("Casi no cambió"))
    @Test fun veredictoConMejora() = assertTrue(DeviceHealth.freeVerdict(600 * 1024, 640 * 1024, 160 * 1024, 158 * 1024).startsWith("Se liberaron 40 MB"))

    @Test fun snoozeVigente() = assertTrue(DeviceHealth.snoozed(1_000, 1_000 + 3_600_000))
    @Test fun snoozeVencido() = assertFalse(DeviceHealth.snoozed(5_000, 4_000))
    @Test fun snoozeConRelojAdelantadoSeIgnora() = assertFalse(DeviceHealth.snoozed(0, 3 * day)) // más de 24 h: dato inválido
}

class KillPolicyTest {
    private val own = "cl.deco.shelf"
    private fun prot(p: String, ime: String? = "com.google.android.inputmethod.latin") = KillPolicy.isProtected(p, ime, own)

    @Test fun protegeLista() {
        for (p in listOf("com.google.android.gms", "com.google.android.gms.persistent", "com.google.android.gsf", "com.android.vending",
            "com.google.android.apps.mediashell", "com.google.android.tv.remote.service", "com.android.systemui", "com.android.tv.settings",
            "com.droidlogic", "com.droidlogic.tv.settings", "com.droidlogic.otaupgrade", "com.sdmc.tms", "com.sdmc.hwinfo",
            "com.android.providers.tv", "com.android.providers.media.module", "com.google.android.tv.providers", "android", "tv.settings"))
            assertTrue(p, prot(p))
    }
    @Test fun protegeLienzo() = assertTrue(prot(own))
    @Test fun protegeImeActivo() = assertTrue(KillPolicy.isProtected("com.acme.teclado", "com.acme.teclado", own))
    @Test fun noProtegeAppsDeUsuario() {
        for (p in listOf("com.netflix.ninja", "com.google.android.youtube.tv", "com.disney.disneyplus", "cl.movistarplay", "com.wbd.stream", "com.arkiv.player.light"))
            assertFalse(p, prot(p))
    }
    @Test fun prefijoNoCoincideParcial() = assertFalse(prot("com.sdmcfake.app")) // "com.sdmc." exige el punto
    @Test fun imePackageDeComponente() {
        assertEquals("com.google.android.inputmethod.latin", KillPolicy.imePackage("com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME"))
        assertEquals(null, KillPolicy.imePackage(null))
        assertEquals(null, KillPolicy.imePackage(""))
    }
}
