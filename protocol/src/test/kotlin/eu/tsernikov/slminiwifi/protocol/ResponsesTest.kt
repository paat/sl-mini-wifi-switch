package eu.tsernikov.slminiwifi.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponsesTest {
    @Test fun capturedSetupResponse() {
        val frames = Frames.parse(vector("wifi_setup.response_ok.hex"))
        val response = Responses.parse(frames.firstDataPayload!!)
        assertEquals(4L, response.id)
        assertEquals(132L, response.apiVersion)
        assertEquals(Status(), response.status)
        assertEquals(0, frames.statusCode)
        assertTrue(response.wifiSetup)
    }

    @Test fun configContainsTwoBandsAndAllDocumentedSettings() {
        val open = Wire.Writer().string(1003, "00:11:22:33:44:55")
            .string(1005, "ExampleNet").varint(1011, 2).string(1017, "wlan0")
            .varint(1013, 1).varint(1015, 1).bytes(2001, byteArrayOf()).toByteArray()
        val protectedBss = Wire.Writer().string(1005, "ExampleNet").varint(1011, 5)
            .bytes(2004, Wire.Writer().string(1, "•••••").toByteArray()).toByteArray()
        val network = Wire.Writer().string(1003, "192.168.1.1/24")
            .string(1011, "local").varint(1014, 42)
            .bytes(1007, open).bytes(1007, protectedBss).toByteArray()
        val config = Wire.Writer().varint(7, 1).varint(31, 1)
            .varint(64, 1).varint(65, 1).varint(66, 1)
            .varint(1076, 1).varint(1077, 1).varint(1078, 1)
            .bytes(1100, network).toByteArray()
        val parsed = configResponse(config).wifiGetConfig!!
        assertTrue(parsed.setupComplete)
        assertTrue(parsed.bypassMode)
        assertTrue(parsed.disable2Ghz)
        assertTrue(parsed.disable5Ghz)
        assertTrue(parsed.disable5GhzHigh)
        assertTrue(parsed.applyDisable2Ghz)
        assertTrue(parsed.applyDisable5Ghz)
        assertTrue(parsed.applyDisable5GhzHigh)
        val lan = parsed.networks.single()
        assertEquals("192.168.1.1/24", lan.ipv4)
        assertEquals("local", lan.domain)
        assertEquals(42L, lan.vlan)
        assertEquals(2, lan.basicServiceSets.size)
        assertEquals(BasicServiceSet("00:11:22:33:44:55", "ExampleNet", 2,
            "wlan0", true, true, AuthKind.OPEN), lan.basicServiceSets[0])
        assertEquals(BasicServiceSet(ssid = "ExampleNet", band = 5,
            authKind = AuthKind.WPA2_WPA3), lan.basicServiceSets[1])
        assertFalse(parsed.toString().contains("•••••"))
    }

    @Test fun allAuthKindsDiscardPasswordStrings() {
        val kinds = listOf(AuthKind.OPEN, AuthKind.WPA2, AuthKind.WPA3, AuthKind.WPA2_WPA3)
        for ((index, kind) in kinds.withIndex()) {
            val password = "never-expose-this-password"
            val auth = Wire.Writer().string(1, password).toByteArray()
            val parsed = bssResponse(Wire.Writer().bytes(2001 + index, auth).toByteArray())
            assertEquals(kind, parsed.authKind)
            assertFalse(parsed.toString().contains(password))
            assertFalse(parsed.javaClass.declaredFields.any { it.name.contains("password", true) })
        }
    }

    @Test fun lastAuthOneofMemberWins() {
        val parsed = bssResponse(Wire.Writer().bytes(2002, byteArrayOf())
            .bytes(2001, byteArrayOf()).toByteArray())
        assertEquals(AuthKind.OPEN, parsed.authKind)
    }

    @Test fun lastResponseOneofMemberWins() {
        val parsed = Responses.parse(Wire.Writer().bytes(3003, byteArrayOf())
            .bytes(1004, Wire.Writer().bytes(1, deviceInfoBytes()).toByteArray()).toByteArray())
        assertFalse(parsed.wifiSetup)
        assertEquals(expectedDeviceInfo(), parsed.deviceInfo)
        val refusedWrite = Responses.parse(Wire.Writer()
            .bytes(1004, Wire.Writer().bytes(1, deviceInfoBytes()).toByteArray())
            .bytes(3001, byteArrayOf()).toByteArray())
        assertNull(refusedWrite.deviceInfo)
        assertFalse(refusedWrite.wifiSetup)
    }

    @Test fun statusAndDeviceInfo() {
        val status = Wire.Writer().varint(1, 9).string(2, "Wifi has already been set up").toByteArray()
        val parsed = Responses.parse(Wire.Writer().varint(1, 123).varint(3, 132)
            .bytes(2, status).bytes(1004, Wire.Writer().bytes(1, deviceInfoBytes()).toByteArray())
            .toByteArray())
        assertEquals(123L, parsed.id)
        assertEquals(132L, parsed.apiVersion)
        assertEquals(Status(9, "Wifi has already been set up"), parsed.status)
        assertEquals(expectedDeviceInfo(), parsed.deviceInfo)
    }

    @Test fun wifiStatusIncludesUptimeWanAndOpaqueDhcpValues() {
        val firstServer = byteArrayOf(1, 2, 3)
        val secondServer = byteArrayOf(4, 5)
        val status = Wire.Writer().bytes(3, deviceInfoBytes())
            .bytes(4, Wire.Writer().varint(1, 86400).toByteArray())
            .string(1003, "100.64.1.2").bytes(1019, firstServer).bytes(1019, secondServer)
            .toByteArray()
        val parsed = Responses.parse(Wire.Writer().bytes(3004, status).toByteArray()).wifiGetStatus!!
        assertEquals(expectedDeviceInfo(), parsed.deviceInfo)
        assertEquals(DeviceState(86400), parsed.deviceState)
        assertEquals("100.64.1.2", parsed.ipv4WanAddress)
        assertEquals(2, parsed.dhcpServers.size)
        assertArrayEquals(firstServer, parsed.dhcpServers[0])
        assertArrayEquals(secondServer, parsed.dhcpServers[1])
    }

    @Test fun absentFieldsHaveProtobufDefaults() {
        assertEquals(Response(), Responses.parse(byteArrayOf()))
        assertEquals(WifiConfig(), configResponse(byteArrayOf()).wifiGetConfig)
        assertEquals(BasicServiceSet(), bssResponse(byteArrayOf()))
        assertEquals(DeviceInfo(), Responses.parse(Wire.Writer().bytes(1004,
            Wire.Writer().bytes(1, byteArrayOf()).toByteArray()).toByteArray()).deviceInfo)
    }

    @Test fun unknownFieldsAreSkippedAtNestedLevels() {
        val unknown = Wire.Writer().varint(500, 123).bytes(501, byteArrayOf(9, 8, 7)).toByteArray()
        val info = Wire.Writer().bytes(1, deviceInfoBytes() + unknown).toByteArray() + unknown
        val parsed = Responses.parse(Wire.Writer().bytes(1004, info).toByteArray() + unknown)
        assertEquals(expectedDeviceInfo(), parsed.deviceInfo)
        assertEquals(AuthKind.OPEN, bssResponse(Wire.Writer().bytes(2001, unknown)
            .toByteArray() + unknown).authKind)
    }

    @Test fun truncatedAndGarbageInputYieldProtocolErrors() {
        val info = Wire.Writer().bytes(1004, Wire.Writer().bytes(1,
            deviceInfoBytes()).toByteArray()).toByteArray()
        assertThrows(ProtocolException::class.java) { Responses.parse(info.copyOf(info.size - 1)) }
        assertThrows(ProtocolException::class.java) { Responses.parse(byteArrayOf(0)) }
        assertThrows(ProtocolException::class.java) {
            Responses.parse(Wire.Writer().bytes(1004, byteArrayOf(10, 2, 1)).toByteArray())
        }
        assertThrows(ProtocolException::class.java) {
            bssResponse(Wire.Writer().bytes(2004, byteArrayOf(10, 4, 65)).toByteArray())
        }
    }

    @Test fun knownFieldsRejectWrongWireTypes() {
        assertThrows(ProtocolException::class.java) {
            Responses.parse(Wire.Writer().bytes(1, byteArrayOf()).toByteArray())
        }
        assertThrows(ProtocolException::class.java) {
            configResponse(Wire.Writer().bytes(7, byteArrayOf()).toByteArray())
        }
        assertThrows(ProtocolException::class.java) {
            bssResponse(Wire.Writer().bytes(2002, Wire.Writer().varint(1, 1).toByteArray()).toByteArray())
        }
    }

    private fun configResponse(config: ByteArray): Response = Responses.parse(
        Wire.Writer().bytes(3009, Wire.Writer().bytes(1, config).toByteArray()).toByteArray(),
    )

    private fun bssResponse(bss: ByteArray): BasicServiceSet = configResponse(
        Wire.Writer().bytes(1100, Wire.Writer().bytes(1007, bss).toByteArray()).toByteArray(),
    ).wifiGetConfig!!.networks.single().basicServiceSets.single()

    private fun deviceInfoBytes(): ByteArray = Wire.Writer().string(1, "Router-0100")
        .string(2, "v4").string(3, "firmware-example").string(4, "EE").toByteArray()

    private fun expectedDeviceInfo() = DeviceInfo("Router-0100", "v4", "firmware-example", "EE")
}
