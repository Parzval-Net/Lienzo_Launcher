package cl.deco.shelf.clock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SleepTimerTest {

    @Test fun calculoYFormatoDeTiempoRestante() {
        val now = 1_000_000L
        assertNull(SleepTimer.remainingMinutes(0L, now))
        assertNull(SleepTimer.remainingMinutes(now, now))
        assertNull(SleepTimer.remainingMinutes(now - 1000L, now))
        assertEquals(1, SleepTimer.remainingMinutes(now + 1L, now))
        assertEquals(1, SleepTimer.remainingMinutes(now + 60_000L, now))
        assertEquals(2, SleepTimer.remainingMinutes(now + 60_001L, now))
        assertEquals(30, SleepTimer.remainingMinutes(now + 30 * 60_000L, now))

        assertEquals("Apagar 30 min", SleepTimer.formatChip(now + 30 * 60_000L, now, english = false))
        assertEquals("Sleep 30 min", SleepTimer.formatChip(now + 30 * 60_000L, now, english = true))
        assertNull(SleepTimer.formatChip(now - 1L, now, english = false))
    }
}
