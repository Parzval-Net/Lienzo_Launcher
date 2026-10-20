package cl.deco.shelf.guard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class CrashLogTest {
    private val min = 60_000L
    private fun dir() = Files.createTempDirectory("crashlog").toFile()

    @Test fun dosCrashesNoActivan() {
        val d = dir(); CrashLog.record(d, 0); CrashLog.record(d, min)
        assertFalse(CrashLog.safeMode(d, 2 * min))
    }

    @Test fun tresEnCincoMinutosActivan() {
        val d = dir(); CrashLog.record(d, 0); CrashLog.record(d, 2 * min); CrashLog.record(d, 4 * min)
        assertTrue(CrashLog.safeMode(d, 5 * min))
    }

    @Test fun tresFueraDeLaVentanaNoActivan() {
        val d = dir(); CrashLog.record(d, 0); CrashLog.record(d, 3 * min); CrashLog.record(d, 6 * min)
        assertFalse(CrashLog.safeMode(d, 7 * min))
    }

    @Test fun sePuedeLimpiarTras10MinutosSinCrashes() {
        val d = dir(); CrashLog.record(d, 0); CrashLog.record(d, min); CrashLog.record(d, 2 * min)
        assertTrue(CrashLog.safeMode(d, 11 * min))
        assertFalse(CrashLog.safeMode(d, 12 * min + 1))
        assertFalse(CrashLog.safeMode(d, 13 * min)) // ya limpio
    }

    @Test fun elBotonLimpia() {
        val d = dir(); CrashLog.record(d, 0); CrashLog.record(d, 1); CrashLog.record(d, 2)
        assertTrue(CrashLog.safeMode(d, 3)); CrashLog.clear(d); assertFalse(CrashLog.safeMode(d, 4))
    }
}
