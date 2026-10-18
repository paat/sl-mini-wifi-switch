package eu.tsernikov.slminiwifi.protocol

data class Status(val code: Int = 0, val message: String = "")

data class Response(
    val id: Long = 0,
    val apiVersion: Long = 0,
    val status: Status = Status(),
    val wifiSetup: Boolean = false,
    val wifiGetConfig: WifiConfig? = null,
    val deviceInfo: DeviceInfo? = null,
    val wifiGetStatus: WifiGetStatusResponse? = null,
)

data class DeviceInfo(
    val id: String = "",
    val hardwareVersion: String = "",
    val softwareVersion: String = "",
    val countryCode: String = "",
)

data class DeviceState(val uptimeS: Long = 0)

data class WifiGetStatusResponse(
    val deviceInfo: DeviceInfo? = null,
    val deviceState: DeviceState? = null,
    val ipv4WanAddress: String = "",
    // PROTOCOL.md identifies field 1019 but does not document its nested schema.
    // Preserve the length-delimited values without inventing a DHCP server model.
    val dhcpServers: List<ByteArray> = emptyList(),
)

data class WifiConfig(
    val setupComplete: Boolean = false,
    val bypassMode: Boolean = false,
    val disable2Ghz: Boolean = false,
    val disable5Ghz: Boolean = false,
    val disable5GhzHigh: Boolean = false,
    val applyDisable2Ghz: Boolean = false,
    val applyDisable5Ghz: Boolean = false,
    val applyDisable5GhzHigh: Boolean = false,
    val networks: List<Network> = emptyList(),
)

data class Network(
    val ipv4: String = "",
    val domain: String = "",
    val vlan: Long = 0,
    val basicServiceSets: List<BasicServiceSet> = emptyList(),
)

enum class AuthKind { OPEN, WPA2, WPA3, WPA2_WPA3 }

data class BasicServiceSet(
    val bssid: String = "",
    val ssid: String = "",
    // Keep the numeric enum so future band values remain distinguishable.
    val band: Int = 0,
    val ifaceName: String = "",
    val disable: Boolean = false,
    val hidden: Boolean = false,
    val authKind: AuthKind? = null,
)

/** Decodes only the documented response subset. Authentication passwords are discarded. */
object Responses {
    fun parse(payload: ByteArray): Response {
        var response = Response()
        fields(payload) { reader, field ->
            response = when (field) {
                1 -> response.copy(id = reader.varint())
                2 -> response.copy(status = status(reader.bytes()))
                3 -> response.copy(apiVersion = reader.varint())
                1004, 3001, 3003, 3004, 3009 -> {
                    // Response.response is a oneof: the last recognized member wins.
                    val cleared = response.copy(
                        wifiSetup = false, wifiGetConfig = null,
                        deviceInfo = null, wifiGetStatus = null,
                    )
                    val bytes = reader.bytes()
                    when (field) {
                        1004 -> cleared.copy(deviceInfo = deviceInfoEnvelope(bytes))
                        3004 -> cleared.copy(wifiGetStatus = wifiStatus(bytes))
                        3009 -> cleared.copy(wifiGetConfig = configEnvelope(bytes))
                        else -> {
                            discardMessage(bytes)
                            cleared.copy(wifiSetup = field == 3003)
                        }
                    }
                }
                else -> { reader.skip(); response }
            }
        }
        return response
    }

    private fun status(bytes: ByteArray): Status {
        var value = Status()
        fields(bytes) { reader, field ->
            value = when (field) {
                1 -> value.copy(code = reader.varint().toInt())
                2 -> value.copy(message = reader.string())
                else -> { reader.skip(); value }
            }
        }
        return value
    }

    private fun deviceInfoEnvelope(bytes: ByteArray): DeviceInfo? {
        var value: DeviceInfo? = null
        fields(bytes) { reader, field ->
            if (field == 1) value = deviceInfo(reader.bytes()) else reader.skip()
        }
        return value
    }

    private fun deviceInfo(bytes: ByteArray): DeviceInfo {
        var value = DeviceInfo()
        fields(bytes) { reader, field ->
            value = when (field) {
                1 -> value.copy(id = reader.string())
                2 -> value.copy(hardwareVersion = reader.string())
                3 -> value.copy(softwareVersion = reader.string())
                4 -> value.copy(countryCode = reader.string())
                else -> { reader.skip(); value }
            }
        }
        return value
    }

    private fun deviceState(bytes: ByteArray): DeviceState {
        var value = DeviceState()
        fields(bytes) { reader, field ->
            if (field == 1) value = DeviceState(reader.varint()) else reader.skip()
        }
        return value
    }

    private fun wifiStatus(bytes: ByteArray): WifiGetStatusResponse {
        var value = WifiGetStatusResponse()
        val servers = mutableListOf<ByteArray>()
        fields(bytes) { reader, field ->
            value = when (field) {
                3 -> value.copy(deviceInfo = deviceInfo(reader.bytes()))
                4 -> value.copy(deviceState = deviceState(reader.bytes()))
                1003 -> value.copy(ipv4WanAddress = reader.string())
                1019 -> { servers.add(reader.bytes()); value }
                else -> { reader.skip(); value }
            }
        }
        return value.copy(dhcpServers = servers.toList())
    }

    private fun configEnvelope(bytes: ByteArray): WifiConfig? {
        var value: WifiConfig? = null
        fields(bytes) { reader, field ->
            if (field == 1) value = config(reader.bytes()) else reader.skip()
        }
        return value
    }

    private fun config(bytes: ByteArray): WifiConfig {
        var value = WifiConfig()
        val networks = mutableListOf<Network>()
        fields(bytes) { reader, field ->
            value = when (field) {
                7 -> value.copy(setupComplete = reader.varint() != 0L)
                31 -> value.copy(bypassMode = reader.varint() != 0L)
                64 -> value.copy(disable2Ghz = reader.varint() != 0L)
                65 -> value.copy(disable5Ghz = reader.varint() != 0L)
                66 -> value.copy(disable5GhzHigh = reader.varint() != 0L)
                1076 -> value.copy(applyDisable2Ghz = reader.varint() != 0L)
                1077 -> value.copy(applyDisable5Ghz = reader.varint() != 0L)
                1078 -> value.copy(applyDisable5GhzHigh = reader.varint() != 0L)
                1100 -> { networks.add(network(reader.bytes())); value }
                else -> { reader.skip(); value }
            }
        }
        return value.copy(networks = networks.toList())
    }

    private fun network(bytes: ByteArray): Network {
        var value = Network()
        val serviceSets = mutableListOf<BasicServiceSet>()
        fields(bytes) { reader, field ->
            value = when (field) {
                1003 -> value.copy(ipv4 = reader.string())
                1011 -> value.copy(domain = reader.string())
                1014 -> value.copy(vlan = reader.varint())
                1007 -> { serviceSets.add(serviceSet(reader.bytes())); value }
                else -> { reader.skip(); value }
            }
        }
        return value.copy(basicServiceSets = serviceSets.toList())
    }

    private fun serviceSet(bytes: ByteArray): BasicServiceSet {
        var value = BasicServiceSet()
        fields(bytes) { reader, field ->
            value = when (field) {
                1003 -> value.copy(bssid = reader.string())
                1005 -> value.copy(ssid = reader.string())
                1011 -> value.copy(band = reader.varint().toInt())
                1017 -> value.copy(ifaceName = reader.string())
                1013 -> value.copy(disable = reader.varint() != 0L)
                1015 -> value.copy(hidden = reader.varint() != 0L)
                2001, 2002, 2003, 2004 -> {
                    discardMessage(reader.bytes(), passwordField = field == 2002 || field == 2004)
                    value.copy(authKind = when (field) {
                        2001 -> AuthKind.OPEN
                        2002 -> AuthKind.WPA2
                        2003 -> AuthKind.WPA3
                        else -> AuthKind.WPA2_WPA3
                    })
                }
                else -> { reader.skip(); value }
            }
        }
        return value
    }

    private fun discardMessage(bytes: ByteArray, passwordField: Boolean = false) {
        fields(bytes) { reader, field ->
            if (passwordField && field == 1) {
                if (reader.wireType != 2) throw ProtocolException("Invalid authentication field wire type")
            }
            // Skip even known password fields, without decoding or retaining their strings.
            reader.skip()
        }
    }

    private inline fun fields(bytes: ByteArray, read: (Wire.Reader, Int) -> Unit) {
        val reader = Wire.Reader(bytes)
        while (true) read(reader, reader.nextField() ?: break)
    }
}
