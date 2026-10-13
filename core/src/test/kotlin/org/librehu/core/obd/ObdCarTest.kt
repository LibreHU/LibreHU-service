package org.librehu.core.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Made-up ECU (not a real car's data): checks the ddt4all syntax and its decoding. */
class ObdCarTest {
    private val ddt4all =
        """
        {
          "ecuname": "TEST UCH",
          "endian": "Big",
          "obd": {"protocol": "CAN", "send_id": "745", "recv_id": "765", "funcaddr": "26"},
          "devices": [],
          "requests": [
            {"name": "Read values", "sentbytes": "2101", "replybytes": "6101", "minbytes": 8,
             "receivebyte_dataitems": {
               "Speed": {"firstbyte": 3},
               "Temperature": {"firstbyte": 5},
               "Door": {"firstbyte": 6, "bitoffset": 2},
               "Mileage": {"firstbyte": 7},
               "Offset 12 bits": {"firstbyte": 6, "bitoffset": 4}
             }},
            {"name": "Read VIN", "sentbytes": "22F190", "replybytes": "62F190",
             "receivebyte_dataitems": {"VIN": {"firstbyte": 4}}},
            {"name": "Write", "sentbytes": "2E0101", "receivebyte_dataitems": {"Speed": {"firstbyte": 3}}},
            {"name": "Reset", "sentbytes": "1101"},
            {"name": "Parametered", "sentbytes": "2102", "sendbyte_dataitems": {"Speed": {"firstbyte": 3}},
             "receivebyte_dataitems": {"Speed": {"firstbyte": 3}}}
          ],
          "data": {
            "Speed": {"bitscount": 16, "bytescount": 2, "scaled": true, "step": 0.01, "unit": "km/h", "format": "0.0"},
            "Temperature": {"scaled": true, "signed": true, "step": 1, "offset": 0, "unit": "°C"},
            "Door": {"bitscount": 1, "lists": {"0": "Closed", "1": "Open"}},
            "Mileage": {"bitscount": 24, "bytescount": 3, "scaled": true, "divideby": 10, "unit": "km"},
            "Offset 12 bits": {"bitscount": 12, "bytescount": 2},
            "VIN": {"bytescount": 17, "bytesascii": true}
          }
        }
        """.trimIndent()

    @Test
    fun ddt4allImportKeepsReadRequestsOnly() {
        val car = ObdCars.fromJson(ddt4all)
        assertEquals("test-uch", car.id)
        val ecu = car.ecus.single()
        assertEquals("745", ecu.sendId)
        assertEquals("765", ecu.recvId)
        assertEquals(listOf("2101", "22F190"), ecu.requests.map { it.send })
        assertEquals(6, car.items().size)
    }

    @Test
    fun decodesLikeDdt4all() {
        val ecu = ObdCars.fromJson(ddt4all).ecus.single()
        val items = ecu.requests[0].items.associateBy { it.name }
        // 61 01 | 1F 40 | F6 | 2C | 01 E2 40 | 00
        val answer = intArrayOf(0x61, 0x01, 0x1F, 0x40, 0xF6, 0x2C, 0x01, 0xE2, 0x40, 0x00)
        fun show(n: String) = ObdCars.decode(n, ecu.name, items.getValue(n), answer)
        assertEquals("80.0 km/h", show("Speed").text) // 0x1F40 = 8000 × 0.01
        assertEquals("-10 °C", show("Temperature").text) // 0xF6 signed
        assertEquals("Open", show("Door").text) // 0x2C = 0010 1100: bit offset 2 from the top = 1
        assertEquals("12345.6 km", show("Mileage").text.replace(',', '.'))
        assertEquals("0C01", show("Offset 12 bits").text) // 0x2C01 bits 4..15, as ddt4all shows it (0c01)
    }

    @Test
    fun asciiAndMultiFrameAnswer() {
        val ecu = ObdCars.fromJson(ddt4all).ecus.single()
        val vin = ecu.requests[1]
        // ELM327 with CAN formatting: length line then numbered frames, last one padded.
        val raw = "014\r0:62F190564631\r1:52424130303030\r2:30303030303132\r\r>"
        val a = ObdCars.answer(raw, vin.service)
        assertTrue(a is EcuAnswer.Positive)
        assertEquals("VF1RBA00000000012", ObdCars.decode("VIN", ecu.name, vin.items.single(), (a as EcuAnswer.Positive).bytes).text)
    }

    @Test
    fun negativeAndPendingAnswers() {
        assertEquals(EcuAnswer.Negative(0x31), ObdCars.answer("7F2131\r\r>", 0x21))
        val ok = ObdCars.answer("7F2178\r6101AA\r\r>", 0x21)
        assertTrue(ok is EcuAnswer.Positive)
        assertTrue(ObdCars.answer("NO DATA\r\r>", 0x21) is EcuAnswer.None)
    }

    @Test
    fun librehuFormatRoundTrip() {
        val car = ObdCars.fromJson(ddt4all).copy(elmInit = listOf("ATSP6"), pids = listOf("RPM"))
        val back = ObdCars.fromJson(ObdCars.toJson(car))
        assertEquals(car, back)
    }

    @Test(expected = ObdCarException::class)
    fun writingAdapterCommandRefused() {
        ObdCars.fromJson("""{"format":"librehu-obd-car","id":"x1","name":"x","elm_init":["ATPP 2C SV 81"],"ecus":[]}""")
    }

    @Test(expected = ObdCarException::class)
    fun writeRequestRefused() {
        ObdCars.fromJson(
            """{"format":"librehu-obd-car","id":"x1","name":"x","ecus":[{"name":"E","send_id":"745","recv_id":"765",
            "requests":[{"send":"2E0101","items":[]}]}]}""",
        )
    }
}
