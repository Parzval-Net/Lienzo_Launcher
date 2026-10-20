package cl.deco.shelf.clock

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class RescheduleTest {
    private lateinit var tzOriginal: TimeZone
    @Before fun guardarZona() { tzOriginal = TimeZone.getDefault() }
    @After fun restaurarZona() { TimeZone.setDefault(tzOriginal) }

    @Test fun manejaLosCincoEventos() {
        for (a in listOf("android.intent.action.BOOT_COMPLETED", "android.intent.action.QUICKBOOT_POWERON", "android.intent.action.TIME_SET",
            "android.intent.action.TIMEZONE_CHANGED", "android.intent.action.MY_PACKAGE_REPLACED")) assertTrue(a, Reschedule.handles(a))
        assertEquals(5, Reschedule.ACTIONS.size)
    }

    @Test fun ignoraOtrosEventos() {
        assertFalse(Reschedule.handles(null))
        assertFalse(Reschedule.handles("android.intent.action.SCREEN_ON"))
        assertFalse(Reschedule.handles("cl.deco.shelf.CLOCK_EVENT"))
    }

    @Test fun reprogramaYLuegoEvaluaElApagadoNocturno() {
        val calls = mutableListOf<String>()
        assertTrue(Reschedule.onEvent(Reschedule.TIMEZONE, { calls += "all" }, { calls += "night" }))
        assertEquals(listOf("all", "night"), calls)
    }

    @Test fun sinEventoValidoNoHaceNada() {
        val calls = mutableListOf<String>()
        assertFalse(Reschedule.onEvent("android.intent.action.SCREEN_OFF", { calls += "all" }, { calls += "night" }))
        assertFalse(Reschedule.onEvent(null, { calls += "all" }, { calls += "night" }))
        assertTrue(calls.isEmpty())
    }

    /** La alarma guarda hora local: tras cambiar la zona, el próximo disparo absoluto cambia (por eso hay que reprogramar). */
    @Test fun elCambioDeZonaMueveElProximoDisparoAbsoluto() {
        val now = 1_792_000_000_000L // instante fijo (oct 2026)
        val alarm = AlarmItem(1, 7, 30, 0b1111111, "", true)
        TimeZone.setDefault(TimeZone.getTimeZone("America/Santiago"))
        val santiago = ClockScheduler.nextTime(alarm, now)
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Madrid"))
        val madrid = ClockScheduler.nextTime(alarm, now)
        assertNotNull(santiago); assertNotNull(madrid)
        assertNotEquals(santiago, madrid)
        // En cada zona el disparo cae a las 07:30 locales y es futuro
        for ((tz, t) in listOf("America/Santiago" to santiago!!, "Europe/Madrid" to madrid!!)) {
            val c = Calendar.getInstance(TimeZone.getTimeZone(tz)).apply { timeInMillis = t }
            assertEquals(tz, 7, c.get(Calendar.HOUR_OF_DAY)); assertEquals(tz, 30, c.get(Calendar.MINUTE))
            assertTrue(tz, t > now)
        }
    }

    @Test fun alarmaUnicaSePasaAManana_siLaHoraYaPaso() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val now = Calendar.getInstance().apply { clear(); set(2026, Calendar.OCTOBER, 20, 8, 0, 0) }.timeInMillis
        val t = ClockScheduler.nextTime(AlarmItem(2, 7, 0, 0, "", true), now)!!
        assertEquals(now + 23 * 3_600_000L, t)
    }

    @Test fun alarmaConDiasRespetaLaMascara() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        // martes 20-oct-2026 08:00; alarma solo lunes (bit0): el próximo es el lunes 26
        val now = Calendar.getInstance().apply { clear(); set(2026, Calendar.OCTOBER, 20, 8, 0, 0) }.timeInMillis
        val t = ClockScheduler.nextTime(AlarmItem(3, 7, 0, 1, "", true), now)!!
        val c = Calendar.getInstance().apply { timeInMillis = t }
        assertEquals(Calendar.MONDAY, c.get(Calendar.DAY_OF_WEEK)); assertEquals(26, c.get(Calendar.DAY_OF_MONTH))
    }

    @Test fun ventanaNocturnaCruzaLaMedianoche() {
        assertTrue(NightMode.inWindow(23 * 60 + 30, 23 * 60, 7 * 60))
        assertTrue(NightMode.inWindow(3 * 60, 23 * 60, 7 * 60))
        assertFalse(NightMode.inWindow(12 * 60, 23 * 60, 7 * 60))
        assertFalse(NightMode.inWindow(7 * 60, 23 * 60, 7 * 60)) // el fin es exclusivo
        assertFalse(NightMode.inWindow(60, 60, 60))
    }
}
