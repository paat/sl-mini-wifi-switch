package eu.tsernikov.slminiwifi.state

import eu.tsernikov.slminiwifi.protocol.ClientResult
import eu.tsernikov.slminiwifi.protocol.DeviceInfo
import eu.tsernikov.slminiwifi.protocol.MiniClient
import eu.tsernikov.slminiwifi.protocol.Transport
import kotlinx.coroutines.delay

enum class WifiOffConnection { Ethernet, Wifi }
enum class WifiOffError { AlreadySetUp, Refused, CantReach }

sealed interface WifiOffOutcome {
    data class Success(val likely: Boolean) : WifiOffOutcome
    data class Failure(val error: WifiOffError) : WifiOffOutcome
}

data class WifiOffObservation(
    val originalWifiPresent: Boolean,
    val routerAnswered: Boolean,
    val dishReachable: Boolean,
    val ipv4Addresses: List<String> = emptyList(),
    val routerInfo: DeviceInfo? = null,
    val dishInfo: DeviceInfo? = null,
)

/**
 * A single confirmed attempt. Constructing this object performs no traffic. The owner must
 * call [confirm] only from the confirmation button, on the same thread as other flow actions.
 * Only read-only observations repeat; a lost setup response never causes a second write.
 */
class WifiOffFlow(
    private val client: MiniClient,
    private val connection: WifiOffConnection,
    private val observe: suspend (remainingMillis: Long) -> WifiOffObservation,
    private val wait: suspend (Long) -> Unit = { delay(it) },
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val requestWritten: () -> Boolean = { false },
) {
    private var attempted = false
    private var outcome: WifiOffOutcome? = null

    suspend fun confirm(state: MiniState): WifiOffOutcome {
        outcome?.let { return it }
        // Set this before the first suspension so rapid repeated taps cannot send twice.
        if (attempted) return WifiOffOutcome.Failure(WifiOffError.CantReach)
        if (state != MiniState.SetupPending) {
            return WifiOffOutcome.Failure(
                if (state == MiniState.Configured || state == MiniState.Bypass) WifiOffError.AlreadySetUp
                else WifiOffError.CantReach,
            )
        }
        attempted = true
        val startedAt = nowMillis()
        val reply = client.setupBypass()
        if (reply is ClientResult.DeviceError) {
            return finish(WifiOffOutcome.Failure(
                if (reply.code == 9) WifiOffError.AlreadySetUp else WifiOffError.Refused,
            ))
        }

        // Link loss alone says nothing about whether the Mini received the change. Only a
        // successful reply or a fully transmitted request permits the weaker "likely" result.
        val changeMayHaveApplied = reply is ClientResult.Ok || requestWritten()
        while (nowMillis() - startedAt < VERIFY_TIMEOUT_MILLIS) {
            val observation = observe(VERIFY_TIMEOUT_MILLIS - (nowMillis() - startedAt))
            if (nowMillis() - startedAt > VERIFY_TIMEOUT_MILLIS) break
            if (connection == WifiOffConnection.Wifi && changeMayHaveApplied && !observation.originalWifiPresent) {
                return finish(WifiOffOutcome.Success(likely = true))
            }
            if (connection == WifiOffConnection.Ethernet && !observation.routerAnswered &&
                observation.dishReachable) {
                if (observation.ipv4Addresses.any(::isStarlinkAddress)) {
                    return finish(WifiOffOutcome.Success(likely = false))
                }
                if (observation.ipv4Addresses.isEmpty() && changeMayHaveApplied) {
                    return finish(WifiOffOutcome.Success(likely = true))
                }
            }
            val remaining = VERIFY_TIMEOUT_MILLIS - (nowMillis() - startedAt)
            if (remaining > 0) wait(minOf(POLL_INTERVAL_MILLIS, remaining))
        }
        return finish(WifiOffOutcome.Failure(WifiOffError.CantReach))
    }

    private fun finish(result: WifiOffOutcome): WifiOffOutcome = result.also { outcome = it }

    companion object {
        const val VERIFY_TIMEOUT_MILLIS = 60_000L
        const val POLL_INTERVAL_MILLIS = 3_000L
    }
}

/** Reads only; any router HTTP reply, including an error or malformed body, means it is up. */
suspend fun probeWifiOff(
    transport: Transport?,
    routerAddress: String,
    originalWifiPresent: Boolean,
    ipv4Addresses: List<String>,
): WifiOffObservation {
    if (transport == null) return WifiOffObservation(originalWifiPresent, false, false, ipv4Addresses)
    var routerAnswered = false
    val routerTransport = object : Transport {
        override suspend fun post(url: String, headers: Map<String, String>, body: ByteArray) =
            transport.post(url, headers, body).also { routerAnswered = true }
    }
    val routerInfo = (MiniClient(routerTransport, "http://$routerAddress:9001").getDeviceInfo()
        as? ClientResult.Ok)?.response?.deviceInfo
    val dishInfo = (MiniClient(transport, "http://192.168.100.1:9201").getDeviceInfo()
        as? ClientResult.Ok)?.response?.deviceInfo
    return WifiOffObservation(originalWifiPresent, routerAnswered, dishInfo != null, ipv4Addresses, routerInfo, dishInfo)
}

/** A Starlink Internet lease, not the Mini router's LAN or WAN status address. */
internal fun isStarlinkAddress(address: String): Boolean {
    val parts = address.split('.')
    if (parts.size != 4) return false
    val octets = parts.map { part ->
        if (part.isEmpty() || part.any { it !in '0'..'9' }) return false
        if (part.length > 1 && part[0] == '0') return false
        part.toIntOrNull() ?: return false
    }
    return octets.all { it in 0..255 } && octets[0] == 100 && octets[1] in 64..127
}
