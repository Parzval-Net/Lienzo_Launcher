package cl.deco.shelf.data.holidays

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

class HolidaysRegionTest {
    private val json = """[
      {"date":"2026-01-01","localName":"Año Nuevo","name":"New Year's Day","counties":null},
      {"date":"2026-06-07","localName":"Asalto y Toma del Morro de Arica","name":"Arica","counties":["CL-AP"]}]"""

    @Test fun soloNacionalesPorDefecto() {
        val l = HolidaysRepository.parseNager(JSONArray(json), 2026, "CL")
        assertEquals(listOf("Año Nuevo"), HolidaysRepository.filterRegion(l, "").map { it.name })
    }

    @Test fun regionIncluyeSusFeriados() {
        val l = HolidaysRepository.parseNager(JSONArray(json), 2026, "CL")
        assertEquals(2, HolidaysRepository.filterRegion(l, "CL-AP").size)
        assertEquals(1, HolidaysRepository.filterRegion(l, "CL-TA").size)
    }
}
