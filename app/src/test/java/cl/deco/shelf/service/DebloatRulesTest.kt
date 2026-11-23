package cl.deco.shelf.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebloatRulesTest {

    @Test fun nuncaDeshabilitaPaquetesCriticosOProtegidos() {
        val installed = listOf(
            "android",
            "cl.deco.shelf",
            "com.android.systemui",
            "com.android.tv.settings",
            "com.android.vending",
            "com.google.android.gms",
            "com.google.android.inputmethod.latin",
            "com.tcl.tv.input",
            "com.tcl.messagebox",
            "com.tcl.shopdemo",
            "com.anoki.acr",
            "com.netflix.ninja",
        )
        // Aunque un protegido extra coincida con SAFE_BLOAT, se respeta la protección
        val targets = DebloatRules.filterDebloatTargets(installed, extraProtected = setOf("com.anoki.acr"))
        assertEquals(listOf("com.tcl.messagebox", "com.tcl.shopdemo"), targets)
        assertFalse(targets.contains("cl.deco.shelf"))
        assertFalse(targets.contains("com.android.tv.settings"))
        assertFalse(targets.contains("com.netflix.ninja"))
    }

    @Test fun forceStopIgnoraSistemaImeAccesibilidadYInputs() {
        val running = listOf(
            "cl.deco.shelf",
            "com.android.systemui",
            "com.google.android.gms",
            "com.google.android.katniss",
            "com.custom.ime",
            "com.custom.accessibility",
            "com.brand.tvinput.hdmi",
            "com.netflix.ninja",
            "com.google.android.youtube.tv",
            "org.xbmc.kodi",
        )
        val targets = DebloatRules.filterForceStopTargets(
            running,
            extraProtected = setOf("com.custom.ime", "com.custom.accessibility"),
        )
        assertEquals(listOf("com.google.android.youtube.tv", "com.netflix.ninja", "org.xbmc.kodi"), targets)
    }

    @Test fun mergeAccessibilityServicePreservaOtrosSinDuplicar() {
        val me = "cl.deco.shelf/cl.deco.shelf.service.KeyService"
        assertEquals(me, ServiceRepair.mergeAccessibilityService(null, me))
        assertEquals("other.pkg/.Svc:$me", ServiceRepair.mergeAccessibilityService("other.pkg/.Svc", me))
        assertEquals("other.pkg/.Svc:$me", ServiceRepair.mergeAccessibilityService("other.pkg/.Svc:$me", me))
        assertTrue(ServiceRepair.removeService("other.pkg/.Svc:$me", "cl.deco.shelf") == "other.pkg/.Svc")
    }
}
