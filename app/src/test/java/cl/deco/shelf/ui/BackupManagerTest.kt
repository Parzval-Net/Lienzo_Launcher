package cl.deco.shelf.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupManagerTest {

    @Test fun excluyeClavesRuntime() {
        assertTrue(BackupManager.isRuntimeKey("last_art_path"))
        assertTrue(BackupManager.isRuntimeKey("hn_low_count"))
        assertTrue(BackupManager.isRuntimeKey("space_low"))
        assertTrue(BackupManager.isRuntimeKey("night_active"))
        assertTrue(BackupManager.isRuntimeKey("sleep_until_ms"))
        assertTrue(BackupManager.isRuntimeKey("boot_pending_ms"))
        assertTrue(BackupManager.isRuntimeKey("disabled_by_lienzo"))
        assertFalse(BackupManager.isRuntimeKey("city_name"))
        assertFalse(BackupManager.isRuntimeKey("dock_max"))
    }

    @Test fun roundTripTodosLosTipos() {
        val original = mapOf(
            "city_name" to "Valparaíso",
            "dock_max" to 6,
            "city_lat" to -330472000L,
            "english" to true,
            "scale_hint" to 1.25f,
            "hidden_apps" to setOf("a.b", "c.d"),
            "last_art_id" to "secret-skip",
            "sleep_until_ms" to 999999L,
        )
        val json = BackupManager.serializePrefs("cl.deco.shelf", original)
        val parsed = BackupManager.parseBackupPrefs(json)

        assertEquals("Valparaíso", parsed["city_name"])
        assertEquals(6, parsed["dock_max"])
        assertEquals(-330472000L, parsed["city_lat"])
        assertEquals(true, parsed["english"])
        assertEquals(1.25f, parsed["scale_hint"] as Float, 0.001f)
        assertEquals(setOf("a.b", "c.d"), parsed["hidden_apps"])
        assertFalse(parsed.containsKey("last_art_id"))
        assertFalse(parsed.containsKey("sleep_until_ms"))
    }
}
