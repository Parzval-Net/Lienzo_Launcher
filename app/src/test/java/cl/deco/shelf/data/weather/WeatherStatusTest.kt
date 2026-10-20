package cl.deco.shelf.data.weather

import cl.deco.shelf.data.art.ArtProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeatherStatusTest {
    private val now = 10L * 3_600_000L
    private fun w(ageMs: Long) = Weather(16.0, 0, emptyList(), now - ageMs)

    @Test fun sinDatoYConRedCarga() = assertEquals(WeatherView.LOADING, WeatherStatus.of(null, true, now))
    @Test fun sinDatoYSinRedMuestraSinConexion() = assertEquals(WeatherView.OFFLINE, WeatherStatus.of(null, false, now))
    @Test fun datoFrescoSinRedSeSigueMostrando() = assertEquals(WeatherView.SHOW, WeatherStatus.of(w(30 * 60_000L), false, now))
    @Test fun datoVencidoSinRedMuestraSinConexion() = assertEquals(WeatherView.OFFLINE, WeatherStatus.of(w(WeatherStatus.STALE_MS + 1), false, now))
    @Test fun datoVencidoConRedSeMuestraMientrasSeRefresca() = assertEquals(WeatherView.SHOW, WeatherStatus.of(w(5 * 3_600_000L), true, now))
    @Test fun umbralExactoNoVence() = assertEquals(WeatherView.SHOW, WeatherStatus.of(w(WeatherStatus.STALE_MS), false, now))
}

class ArtProgressTest {
    @Test fun descargandoMuestraAvance() = assertEquals("Obras 12/60", ArtProgress.chipText(12, 60, true))
    @Test fun enIngles() = assertEquals("Artworks 12/60", ArtProgress.chipText(12, 60, true, english = true))
    @Test fun alTerminarDesaparece() = assertNull(ArtProgress.chipText(60, 60, true))
    @Test fun sinDescargaActivaNoSeMuestra() = assertNull(ArtProgress.chipText(12, 60, false))
    @Test fun sinCatalogoNoSeMuestra() = assertNull(ArtProgress.chipText(0, 0, true))
    @Test fun inicioMuestraCero() = assertEquals("Obras 0/60", ArtProgress.chipText(0, 60, true))
}
