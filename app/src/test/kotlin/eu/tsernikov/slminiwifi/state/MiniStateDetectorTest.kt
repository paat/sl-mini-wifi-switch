package eu.tsernikov.slminiwifi.state

import eu.tsernikov.slminiwifi.protocol.AuthKind
import eu.tsernikov.slminiwifi.protocol.Frames
import eu.tsernikov.slminiwifi.protocol.HttpResponse
import eu.tsernikov.slminiwifi.protocol.Requests
import eu.tsernikov.slminiwifi.protocol.Transport
import eu.tsernikov.slminiwifi.protocol.Wire
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.*
import org.junit.Test

class MiniStateDetectorTest {
    private var now = 0L
    private val detector = MiniStateDetector { now }
    private val fake = FakeTransport()

    private class FakeTransport : Transport {
        var routerUp = true
        var dishUp = true
        var setupComplete = false
        var configReply: HttpResponse? = null
        var infoReply: HttpResponse? = null
        var statusReply: HttpResponse? = null
        var beforeCall: () -> Unit = {}
        val calls = mutableListOf<Pair<String, Int>>()

        override suspend fun post(url: String, headers: Map<String, String>, body: ByteArray): HttpResponse {
            beforeCall()
            val request = when {
                body.contentEquals(Requests.wifiGetConfig()) -> 3009
                body.contentEquals(Requests.getDeviceInfo()) -> 1008
                body.contentEquals(Requests.getStatus()) -> 1004
                else -> error("Unexpected request: the detector must send documented reads only")
            }
            calls += url to request
            if (url.startsWith("http://192.168.100.1:9201/")) {
                check(request == 1008) { "Only device info is requested from the dish" }
                if (!dishUp) throw IOException("No dish route")
                return deviceInfo("mini1_prod1", "dish-version")
            }
            if (!routerUp) throw IOException("No router route")
            return when (request) {
                3009 -> configReply ?: configuration(setupComplete)
                1008 -> infoReply ?: deviceInfo("v4", "router-version")
                1004 -> statusReply ?: response(3004, Wire.Writer()
                    .bytes(3, Wire.Writer().string(2, "v4").string(3, "status-version").toByteArray())
                    .string(1003, "100.76.233.16").toByteArray())
                else -> error("Unexpected read")
            }
        }
    }

    private fun detect(
        transport: Transport? = fake,
        routerAddress: String = "192.168.1.1",
        ipv4Addresses: List<String> = listOf("100.64.0.1"),
    ) = runCall { detector.detect(transport, routerAddress, ipv4Addresses) }

    @Test fun setupPendingIncludesVersionsBandsAddressesAndAuthentication() {
        val result = detect()
        assertEquals(MiniState.SetupPending, result.state)
        assertEquals("v4", result.details.routerInfo?.hardwareVersion)
        assertEquals("router-version", result.details.routerInfo?.softwareVersion)
        assertEquals("mini1_prod1", result.details.dishInfo?.hardwareVersion)
        assertEquals("dish-version", result.details.dishInfo?.softwareVersion)
        assertEquals("100.76.233.16", result.details.status?.ipv4WanAddress)
        val network = result.details.config!!.networks.single()
        assertEquals("192.168.1.1/24", network.ipv4)
        assertEquals(listOf(2, 5), network.basicServiceSets.map { it.band })
        assertEquals(listOf("STARLINK", "STARLINK"), network.basicServiceSets.map { it.ssid })
        assertEquals(listOf(AuthKind.OPEN, AuthKind.OPEN), network.basicServiceSets.map { it.authKind })
    }

    @Test fun missingSetupCompleteFieldMeansSetupPending() {
        fake.configReply = response(3009, Wire.Writer().bytes(1, byteArrayOf()).toByteArray())
        assertEquals(MiniState.SetupPending, detect().state)
    }

    @Test fun configuredRouterHasPriorityOverReachableDish() {
        fake.setupComplete = true
        assertEquals(MiniState.Configured, detect().state)
    }

    @Test fun silentRouterReachableDishAndCgnatLeaseMeanBypass() {
        fake.routerUp = false
        val result = detect()
        assertEquals(MiniState.Bypass, result.state)
        assertNull(result.details.routerInfo)
        assertNotNull(result.details.dishInfo)
        assertEquals(2, fake.calls.size)
    }

    @Test fun reachableDishAndOneRouterFailureCannotClaimWifiOff() {
        fake.routerUp = false
        for (addresses in listOf(emptyList(), listOf("192.168.1.2"), listOf("100.63.255.255"), listOf("100.128.0.1"))) {
            assertEquals(MiniState.NotFound, detect(ipv4Addresses = addresses).state)
        }
    }

    @Test fun repeatedRouterFailuresWithoutCgnatStillDoNotProveWifiOff() {
        detect(ipv4Addresses = listOf("192.168.1.2"))
        fake.routerUp = false
        repeat(3) {
            now += 3_000
            assertEquals(MiniState.Rebooting, detect(ipv4Addresses = listOf("192.168.1.2")).state)
        }
    }

    @Test fun intentionalNetworkReleaseClearsRestartHistory() {
        detect()
        detector.clearHistory()
        now += 1_000
        assertEquals(MiniState.NotFound, detect(null).state)
        assertNull(detector.rebootTimeRemainingMillis)
    }

    @Test fun timedOutProbeRetainsDetailsAndMarksUncertaintyWithoutRecordingAbsence() {
        val initial = detect()
        now += 3_000
        detect(null)
        now += MiniStateDetector.REBOOT_TIMEOUT_MILLIS
        val result = detector.probeTimedOut()
        assertEquals(MiniState.NotFound, result.state)
        assertTrue(result.probeTimedOut)
        assertEquals(initial.details, result.details)
        assertNull(detector.rebootTimeRemainingMillis)
        assertFalse(detect(null).probeTimedOut)
    }

    @Test fun failedEarlyCandidateDoesNotEraseRestartHistory() {
        detect()
        now += 3_000
        fake.configReply = HttpResponse(503, emptyMap(), byteArrayOf())
        assertEquals(MiniState.NotFound, runCall { detector.detect(fake, recordMiss = false) }.state)
        assertEquals(MiniState.Rebooting, detect(null).state)
    }

    @Test fun initiallySilentDevicesMeanNotFound() {
        fake.routerUp = false
        fake.dishUp = false
        assertEquals(MiniState.NotFound, detect().state)
    }

    @Test fun noNetworkMeansNotFoundWithoutTraffic() {
        assertEquals(MiniState.NotFound, detect(null).state)
        assertTrue(fake.calls.isEmpty())
    }

    @Test fun eachReachableStateCanTransitionToEveryReachableState() {
        val reachable = listOf(MiniState.SetupPending, MiniState.Configured, MiniState.Bypass)
        for (from in reachable) for (to in reachable) {
            val subject = MiniStateDetector { now }
            setState(from)
            assertEquals(from, runCall { subject.detect(fake, ipv4Addresses = listOf("100.64.0.1")) }.state)
            setState(to)
            now += 3_000
            assertEquals("$from → $to", to, runCall { subject.detect(fake, ipv4Addresses = listOf("100.64.0.1")) }.state)
        }
    }

    @Test fun eachReachableStateCanRestartAndRecoverToEveryReachableState() {
        val reachable = listOf(MiniState.SetupPending, MiniState.Configured, MiniState.Bypass)
        for (from in reachable) for (to in reachable) {
            val subject = MiniStateDetector { now }
            setState(from)
            val initial = runCall { subject.detect(fake, ipv4Addresses = listOf("100.64.0.1")) }
            now += 3_000
            fake.routerUp = false
            fake.dishUp = false
            val lost = runCall { subject.detect(fake, ipv4Addresses = listOf("100.64.0.1")) }
            assertEquals("$from → Rebooting", MiniState.Rebooting, lost.state)
            assertEquals(initial.details, lost.details)
            now += 30_000
            assertEquals(MiniState.Rebooting, runCall { subject.detect(null) }.state)
            setState(to)
            assertEquals("Rebooting → $to", to, runCall { subject.detect(fake, ipv4Addresses = listOf("100.64.0.1")) }.state)
        }
    }

    @Test fun rebootExpiresAtThreeMinutesAndDoesNotRestartItsTimer() {
        detect()
        now = 3_000
        assertEquals(MiniState.Rebooting, detect(null).state)
        now = 182_999
        assertEquals(MiniState.Rebooting, detect(null).state)
        now = 183_000
        assertEquals(MiniState.NotFound, detect(null).state)
        now += 3_000
        assertEquals(MiniState.NotFound, detect(null).state)
    }

    @Test fun remainingRestartTimeTracksTheClockAndClearsAfterRecoveryOrExpiry() {
        assertNull(detector.rebootTimeRemainingMillis)
        detect()
        assertNull(detector.rebootTimeRemainingMillis)
        now = 3_000
        detect(null)
        assertEquals(180_000L, detector.rebootTimeRemainingMillis)
        now += 12_000
        assertEquals(168_000L, detector.rebootTimeRemainingMillis)
        detect()
        assertNull(detector.rebootTimeRemainingMillis)
        now += 3_000
        detect(null)
        now += 180_001
        assertEquals(0L, detector.rebootTimeRemainingMillis)
        assertEquals(MiniState.NotFound, detect(null).state)
        assertNull(detector.rebootTimeRemainingMillis)
    }

    @Test fun notFoundCanRecoverToEveryReachableState() {
        for (target in listOf(MiniState.SetupPending, MiniState.Configured, MiniState.Bypass)) {
            val subject = MiniStateDetector { now }
            assertEquals(MiniState.NotFound, runCall { subject.detect(null) }.state)
            setState(target)
            assertEquals(target, runCall { subject.detect(fake, ipv4Addresses = listOf("100.64.0.1")) }.state)
        }
    }

    @Test fun staleReachabilityDoesNotImplyRestart() {
        for (source in listOf(MiniState.SetupPending, MiniState.Configured, MiniState.Bypass)) {
            setState(source)
            detect()
            now += 6_001
            assertEquals(MiniState.NotFound, detect(null).state)
        }
    }

    @Test fun slowFailedProbesStillRecognizeRecentReachability() {
        detect()
        now = 3_000
        fake.routerUp = false
        fake.dishUp = false
        fake.beforeCall = { now += 10_000 }
        assertEquals(MiniState.Rebooting, detect().state)
    }

    @Test fun recoveryStartsAFreshRestartWindow() {
        detect()
        now = 3_000
        detect(null)
        now = 170_000
        assertEquals(MiniState.SetupPending, detect().state)
        now += 3_000
        assertEquals(MiniState.Rebooting, detect(null).state)
        now += 170_000
        assertEquals(MiniState.Rebooting, detect(null).state)
    }

    @Test fun aReplyingRouterWithUnavailableConfigNeverMeansSetupOrBypass() {
        for (reply in listOf(
            HttpResponse(200, mapOf("grpc-status" to "7"), byteArrayOf()),
            HttpResponse(503, emptyMap(), byteArrayOf()),
            HttpResponse(200, mapOf("grpc-status" to "0"), byteArrayOf(0)),
        )) {
            fake.configReply = reply
            assertEquals(MiniState.NotFound, detect().state)
        }
    }

    @Test fun aReplyingRouterWithUnavailableConfigIsNotCalledRestarting() {
        detect()
        now += 3_000
        fake.configReply = HttpResponse(200, mapOf("grpc-status" to "7"), byteArrayOf())
        assertEquals(MiniState.NotFound, detect().state)
    }

    @Test fun unavailableDetailsDoNotPreventSetupDetection() {
        fake.infoReply = HttpResponse(200, mapOf("grpc-status" to "7"), byteArrayOf())
        fake.statusReply = fake.infoReply
        fake.dishUp = false
        val result = detect()
        assertEquals(MiniState.SetupPending, result.state)
        assertNull(result.details.routerInfo)
        assertNull(result.details.dishInfo)
        assertNull(result.details.status)
    }

    @Test fun routerVersionFallsBackToStatusDeviceInfo() {
        fake.infoReply = HttpResponse(200, mapOf("grpc-status" to "7"), byteArrayOf())
        assertEquals("status-version", detect().details.routerInfo?.softwareVersion)
    }

    @Test fun dhcpGatewayAndFixedDishEndpointReceiveOnlyDocumentedReads() {
        val result = detect(routerAddress = "192.168.2.1")
        assertEquals("192.168.2.1", result.details.routerAddress)
        assertEquals(listOf(
            "http://192.168.2.1:9001/SpaceX.API.Device.Device/Handle" to 3009,
            "http://192.168.2.1:9001/SpaceX.API.Device.Device/Handle" to 1008,
            "http://192.168.2.1:9001/SpaceX.API.Device.Device/Handle" to 1004,
            "http://192.168.100.1:9201/SpaceX.API.Device.Device/Handle" to 1008,
        ), fake.calls)
    }

    @Test fun cancellationPropagatesWithoutMoreRequests() {
        val cancelled = CancellationException("Screen closed")
        fake.beforeCall = { throw cancelled }
        assertSame(cancelled, assertThrows(CancellationException::class.java) { detect() })
        assertTrue(fake.calls.isEmpty())
    }

    private fun setState(state: MiniState) {
        fake.routerUp = state != MiniState.Bypass
        fake.dishUp = true
        fake.setupComplete = state == MiniState.Configured
    }

    companion object {
        private fun response(field: Int, value: ByteArray) = HttpResponse(
            200, mapOf("grpc-status" to "0"),
            Frames.request(Wire.Writer().varint(1, 1).bytes(field, value).toByteArray()),
        )

        private fun deviceInfo(hardware: String, software: String) = response(1004,
            Wire.Writer().bytes(1, Wire.Writer().string(2, hardware).string(3, software).toByteArray()).toByteArray())

        private fun configuration(complete: Boolean): HttpResponse {
            val network = Wire.Writer().string(1003, "192.168.1.1/24")
            for (band in listOf(2L, 5L)) network.bytes(1007, Wire.Writer()
                .string(1005, "STARLINK").varint(1011, band).bytes(2001, byteArrayOf()).toByteArray())
            return response(3009, Wire.Writer().bytes(1, Wire.Writer()
                .varint(7, if (complete) 1 else 0).bytes(1100, network.toByteArray()).toByteArray()).toByteArray())
        }

        // These fakes complete inline; no Android or scheduler is needed for state tests.
        private fun <T> runCall(block: suspend () -> T): T {
            var outcome: Result<T>? = null
            block.startCoroutine(object : Continuation<T> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(result: Result<T>) { outcome = result }
            })
            return checkNotNull(outcome) { "Fake transport unexpectedly suspended" }.getOrThrow()
        }
    }
}
