package cl.deco.shelf.data.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeocodingTest {
    private val json = """{"results":[
      {"id":3868707,"name":"Valdivia","latitude":-39.81422,"longitude":-73.24589,"country_code":"CL","timezone":"America/Santiago","country":"Chile","admin1":"Región de Los Ríos"},
      {"id":11886309,"name":"Valdivia","latitude":7.16433,"longitude":-75.43906,"country_code":"CO","timezone":"America/Bogota","country":"Colombia","admin1":"Antioquia"},
      {"id":1,"name":"Sin coordenadas"}],"generationtime_ms":0.2}"""

    @Test fun parseaResultadosYDescartaIncompletos() {
        val l = Geocoding.parse(json)
        assertEquals(2, l.size)
        assertEquals("Valdivia, Región de Los Ríos, Chile", l[0].label)
        assertEquals(-39.81422, l[0].lat, 1e-6)
        assertEquals("America/Bogota", l[1].tz)
    }

    @Test fun sinResultadosDevuelveListaVacia() = assertTrue(Geocoding.parse("""{"generationtime_ms":0.1}""").isEmpty())
}
