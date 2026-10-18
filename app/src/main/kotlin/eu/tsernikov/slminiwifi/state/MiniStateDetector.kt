package eu.tsernikov.slminiwifi.state

import eu.tsernikov.slminiwifi.protocol.ClientResult
import eu.tsernikov.slminiwifi.protocol.DeviceInfo
import eu.tsernikov.slminiwifi.protocol.MiniClient
import eu.tsernikov.slminiwifi.protocol.Transport
import eu.tsernikov.slminiwifi.protocol.WifiConfig
import eu.tsernikov.slminiwifi.protocol.WifiGetStatusResponse

enum class MiniState { SetupPending, Configured, Bypass, Rebooting, NotFound }

data class MiniDetails(
    val routerInfo: DeviceInfo? = null,
    val dishInfo: DeviceInfo? = null,
    val config: WifiConfig? = null,
    val status: WifiGetStatusResponse? = null,
    val routerAddress: String = "192.168.1.1",
)

data class MiniSnapshot(
    val state: MiniState,
    val details: MiniDetails = MiniDetails(),
    val probeTimedOut: Boolean = false,
)

/**
 * Read-only probes and state transitions, independent of Android. Call serially for one Mini.
 * A missing network is passed as null. The owner schedules polling and cancels it on leaving.
 */
class MiniStateDetector(private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private var lastReachableAt: Long? = null
    private var lostAt: Long? = null
    private var lastDetails = MiniDetails()

    /** Null outside restart waiting; the owner can cap both its next probe and poll delay. */
    val rebootTimeRemainingMillis: Long?
        get() = lostAt?.let { (REBOOT_TIMEOUT_MILLIS - (nowMillis() - it)).coerceAtLeast(0L) }

    /** Intentional release cannot start a restart wait, or erase a wait already in progress. */
    fun clearHistory() {
        if (lostAt == null) lastReachableAt = null
    }

    /** A timeout keeps the original restart deadline and exposes the uncertain probe. */
    fun probeTimedOut(): MiniSnapshot = unreachable(nowMillis(), lastDetails).copy(probeTimedOut = true)

    /** Skip recording a miss while the owner still has other network candidates to probe. */
    suspend fun detect(
        transport: Transport?,
        routerAddress: String = "192.168.1.1",
        ipv4Addresses: List<String> = emptyList(),
        recordMiss: Boolean = true,
    ): MiniSnapshot {
        val startedAt = nowMillis()
        if (transport == null) return if (recordMiss) unreachable(startedAt, MiniDetails(routerAddress = routerAddress))
            else MiniSnapshot(MiniState.NotFound, MiniDetails(routerAddress = routerAddress))

        // An HTTP error or malformed answer still means the router is not silent. Do not
        // misreport Wi-Fi as off merely because config is refused or a schema has changed.
        var routerAnswered = false
        val routerTransport = object : Transport {
            override suspend fun post(url: String, headers: Map<String, String>, body: ByteArray) =
                transport.post(url, headers, body).also { routerAnswered = true }
        }
        val router = MiniClient(routerTransport, "http://$routerAddress:9001")
        val config = (router.wifiGetConfig() as? ClientResult.Ok)?.response?.wifiGetConfig
        // Once the router has timed out, extra reads to the same endpoint only prolong waiting.
        val info = if (routerAnswered) (router.getDeviceInfo() as? ClientResult.Ok)?.response?.deviceInfo else null
        val status = if (routerAnswered) (router.getStatus() as? ClientResult.Ok)?.response?.wifiGetStatus else null
        val dishInfo = (MiniClient(transport, "http://192.168.100.1:9201").getDeviceInfo()
            as? ClientResult.Ok)?.response?.deviceInfo
        val details = MiniDetails(info ?: status?.deviceInfo, dishInfo, config, status, routerAddress)
        val state = when {
            config != null -> if (config.setupComplete) MiniState.Configured else MiniState.SetupPending
            !routerAnswered && dishInfo != null && ipv4Addresses.any(::isStarlinkAddress) -> MiniState.Bypass
            else -> null
        }
        if (state != null) {
            lastReachableAt = nowMillis()
            lostAt = null
            lastDetails = details
            return MiniSnapshot(state, details)
        }
        if (!recordMiss) return MiniSnapshot(MiniState.NotFound, details)
        if (routerAnswered) {
            // We cannot determine setup state without config. Never offer setup based on a guess.
            lastReachableAt = null
            lostAt = null
            return MiniSnapshot(MiniState.NotFound, details)
        }
        return unreachable(startedAt, details)
    }

    private fun unreachable(startedAt: Long, details: MiniDetails): MiniSnapshot {
        val lastSeen = lastReachableAt
        // Check the start, not end, of the probe: network timeouts may take several seconds.
        // One extra poll interval tolerates scheduling jitter without treating stale history as a reboot.
        if (lostAt == null && lastSeen != null && startedAt - lastSeen in 0..(2 * POLL_INTERVAL_MILLIS)) {
            lostAt = startedAt
        }
        val lost = lostAt
        if (lost != null && nowMillis() - lost in 0 until REBOOT_TIMEOUT_MILLIS) {
            return MiniSnapshot(MiniState.Rebooting, lastDetails)
        }
        lastReachableAt = null
        lostAt = null
        return MiniSnapshot(MiniState.NotFound, details)
    }

    companion object {
        const val POLL_INTERVAL_MILLIS = 3_000L
        const val REBOOT_TIMEOUT_MILLIS = 180_000L
    }
}
