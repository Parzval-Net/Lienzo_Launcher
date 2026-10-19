package cl.deco.shelf.data.weather

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class WeatherDisplayTest {
    private fun w(code: Int, rain: Int?, today: Int) =
        Weather(21.0, code, listOf(DayForecast(LocalDate.of(2026, 10, 19), 9.0, 22.0, today, 86)), 0L, rainSoonProb = rain)

    @Test fun despejadoConLluviaProbableMuestraPrecipitacionDelDia() = assertEquals(53, w(0, 86, 53).displayCode)
    @Test fun despejadoConLluviaProbableSinCodigoDiarioUsaLluvia() = assertEquals(61, w(0, 70, 2).displayCode)
    @Test fun probabilidadBajaMantieneActual() = assertEquals(0, w(0, 20, 53).displayCode)
    @Test fun yaLloviendoMantieneActual() = assertEquals(63, w(63, 90, 53).displayCode)
    @Test fun sinDatoHorarioMantieneActual() = assertEquals(1, w(1, null, 53).displayCode)
}
