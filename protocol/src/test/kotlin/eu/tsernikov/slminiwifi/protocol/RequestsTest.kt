package eu.tsernikov.slminiwifi.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RequestsTest {
    @Test fun deviceInfoVector() =
        assertArrayEquals(vector("get_device_info.request.hex"), Requests.getDeviceInfo())

    @Test fun configVector() =
        assertArrayEquals(vector("wifi_get_config.request.hex"), Requests.wifiGetConfig())

    @Test fun statusVector() =
        assertArrayEquals(vector("wifi_get_status.request.hex"), Requests.getStatus())

    @Test fun bypassVector() =
        assertArrayEquals(vector("wifi_setup_bypass.request.hex"), Requests.setupBypass())

    @Test fun nameVector() =
        assertArrayEquals(vector("wifi_setup_name.request.hex"), Requests.setupWifi("camera2.4"))

    @Test fun passwordVector() = assertArrayEquals(
        vector("wifi_setup_name_password.request.hex"),
        Requests.setupWifi("ExampleNet", "example-pass-123"),
    )

    @Test fun allBuildersAcceptCustomUnsignedIds() {
        for (id in listOf(0L, 128L, Long.MAX_VALUE, -1L)) {
            val requests = listOf(
                Requests.getDeviceInfo(id), Requests.wifiGetConfig(id), Requests.getStatus(id),
                Requests.setupBypass(id), Requests.setupWifi("name", id = id),
            )
            for (request in requests) {
                val reader = Wire.Reader(Frames.parse(request).firstDataPayload!!)
                assertEquals(1, reader.nextField())
                assertEquals(id, reader.varint())
                reader.nextField()
                reader.skip()
                assertNull(reader.nextField())
            }
        }
    }

    @Test fun unicodeUsesUtf8ByteLengthsAndNullOmitsPassword() {
        val reader = Wire.Reader(Frames.parse(Requests.setupWifi("café 📡")).firstDataPayload!!)
        reader.nextField()
        reader.skip()
        assertEquals(3003, reader.nextField())
        val setup = Wire.Reader(reader.bytes())
        assertEquals(2, setup.nextField())
        assertEquals("café 📡", setup.string())
        assertNull(setup.nextField())
    }
}
