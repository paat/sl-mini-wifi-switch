package eu.tsernikov.slminiwifi.state

import eu.tsernikov.slminiwifi.protocol.Frames
import eu.tsernikov.slminiwifi.protocol.HttpResponse
import eu.tsernikov.slminiwifi.protocol.MiniClient
import eu.tsernikov.slminiwifi.protocol.Requests
import eu.tsernikov.slminiwifi.protocol.Transport
import eu.tsernikov.slminiwifi.protocol.Wire
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.*
import org.junit.Test

class WifiOffFlowTest {
    private var now = 0L
    private val fake = FakeTransport()
    private var wifiPresent = true
    private var requestWritten = false
    private var addresses = listOf("100.64.0.1")
    private var beforeProbe: () -> Unit = {}
    private val budgets = mutableListOf<Long>()

    private class FakeTransport : Transport {
        var setupReply = response(3003)
        var setupFailure: Exception? = null
        var onSetup: () -> Unit = {}
        var routerReply: HttpResponse? = null
        var dishUp = true
        val calls = mutableListOf<Pair<String, ByteArray>>()
        val writes get() = calls.count { it.second.contentEquals(Requests.setupBypass()) }

        override suspend fun post(url: String, headers: Map<String, String>, body: ByteArray): HttpResponse {
            calls += url to body
            if (body.contentEquals(Requests.setupBypass())) {
                check(url.startsWith("http://192.168.1.1:9001/"))
                onSetup()
                setupFailure?.let { throw it }
                return setupReply
            }
            check(body.contentEquals(Requests.getDeviceInfo())) { "Unexpected request" }
            if (url.startsWith("http://192.168.100.1:9201/")) {
                if (!dishUp) throw IOException("No route to dish")
                return response(1004, Wire.Writer().bytes(1, Wire.Writer().string(2, "mini").toByteArray()).toByteArray())
            }
            return routerReply ?: throw IOException("No route to router")
        }
    }

    private fun flow(connection: WifiOffConnection = WifiOffConnection.Ethernet) = WifiOffFlow(
        client = MiniClient(fake, "http://192.168.1.1:9001"),
        connection = connection,
        observe = { remaining ->
            budgets += remaining
            beforeProbe()
            probeWifiOff(fake, "192.168.1.1", wifiPresent, addresses)
        },
        wait = { now += it },
        nowMillis = { now },
        requestWritten = { requestWritten },
    )

    @Test fun noRequestsBeforeConfirmationAndNoWritesForNonPendingStates() {
        val subject = flow()
        assertTrue(fake.calls.isEmpty())
        for (state in MiniState.entries.filter { it != MiniState.SetupPending }) {
            assertTrue(runCall { subject.confirm(state) } is WifiOffOutcome.Failure)
        }
        assertTrue(fake.calls.isEmpty())
    }

    @Test fun ethernetSuccessRequiresSilentRouterReachableDishAndStarlinkAddress() {
        assertEquals(WifiOffOutcome.Success(false), runCall { flow().confirm(MiniState.SetupPending) })
        assertEquals(1, fake.writes)
        assertEquals(3, fake.calls.size)
        assertEquals(listOf(60_000L), budgets)
    }

    @Test fun droppedWifiAfterSetupReplyIsLikelySuccess() {
        wifiPresent = false
        fake.dishUp = false
        assertEquals(WifiOffOutcome.Success(true), runCall { flow(WifiOffConnection.Wifi).confirm(MiniState.SetupPending) })
        assertEquals(1, fake.writes)
    }

    @Test fun droppedWifiWithLostSetupReplyIsLikelySuccessWithoutRetry() {
        wifiPresent = false
        fake.setupFailure = SocketTimeoutException("Reply lost")
        requestWritten = true
        val subject = flow(WifiOffConnection.Wifi)
        repeat(2) {
            assertEquals(WifiOffOutcome.Success(true), runCall { subject.confirm(MiniState.SetupPending) })
        }
        assertEquals(1, fake.writes)
    }

    @Test fun statusNineIsAlreadySetUpWithoutVerificationOrRetry() {
        fake.setupReply = HttpResponse(200, mapOf("grpc-status" to "9"), byteArrayOf())
        val subject = flow()
        repeat(2) {
            assertEquals(WifiOffOutcome.Failure(WifiOffError.AlreadySetUp), runCall { subject.confirm(MiniState.SetupPending) })
        }
        assertEquals(1, fake.calls.size)
    }

    @Test fun statusSevenIsRefusedEvenIfWifiDrops() {
        requestWritten = true
        fake.setupReply = HttpResponse(200, mapOf("grpc-status" to "7"), byteArrayOf())
        wifiPresent = false
        assertEquals(WifiOffOutcome.Failure(WifiOffError.Refused), runCall { flow(WifiOffConnection.Wifi).confirm(MiniState.SetupPending) })
        assertEquals(1, fake.calls.size)
    }

    @Test fun embeddedStatusIsMappedLikeTransportStatus() {
        fake.setupReply = HttpResponse(200, mapOf("grpc-status" to "0"), Frames.request(
            Wire.Writer().varint(1, 1).bytes(2, Wire.Writer().varint(1, 9).toByteArray()).toByteArray(),
        ))
        assertEquals(WifiOffOutcome.Failure(WifiOffError.AlreadySetUp), runCall { flow().confirm(MiniState.SetupPending) })
        assertEquals(1, fake.calls.size)
    }

    @Test fun timeoutWithWifiStillPresentCannotClaimSuccessOrRetry() {
        fake.setupFailure = SocketTimeoutException("Timed out")
        val subject = flow(WifiOffConnection.Wifi)
        repeat(2) {
            assertEquals(WifiOffOutcome.Failure(WifiOffError.CantReach), runCall { subject.confirm(MiniState.SetupPending) })
        }
        assertEquals(60_000L, now)
        assertEquals(1, fake.writes)
        assertEquals(20, budgets.size)
        assertTrue(budgets.all { it in 1..60_000 })
    }

    @Test fun noRouteAndNoDevicesCannotClaimSuccessOrRetry() {
        fake.setupFailure = IOException("No route")
        fake.dishUp = false
        assertEquals(WifiOffOutcome.Failure(WifiOffError.CantReach), runCall { flow().confirm(MiniState.SetupPending) })
        assertEquals(60_000L, now)
        assertEquals(1, fake.writes)
    }

    @Test fun lostEthernetReplyCanStillBeVerifiedByReadOnlyProbes() {
        fake.setupFailure = IOException("Link changed")
        assertEquals(WifiOffOutcome.Success(false), runCall { flow().confirm(MiniState.SetupPending) })
        assertEquals(1, fake.writes)
    }

    @Test fun staleEthernetAddressWaitsForNewLease() {
        addresses = listOf("192.168.1.100")
        beforeProbe = { if (now >= 9_000) addresses = listOf("100.127.255.254") }
        assertEquals(WifiOffOutcome.Success(false), runCall { flow().confirm(MiniState.SetupPending) })
        assertEquals(9_000L, now)
        assertEquals(1, fake.writes)
    }

    @Test fun visibleAddressesOutsideStarlinkRangeDoNotVerifyEthernet() {
        for (address in listOf("100.63.255.255", "100.128.0.1", "192.168.1.2", "100.64.0.256")) {
            addresses = listOf(address)
            assertEquals(address, WifiOffOutcome.Failure(WifiOffError.CantReach), runCall { flow().confirm(MiniState.SetupPending) })
        }
        assertEquals(4, fake.writes)
    }

    @Test fun unavailableAddressOnlyAllowsLikelySuccessAfterConfirmedWrite() {
        addresses = emptyList()
        assertEquals(WifiOffOutcome.Success(true), runCall { flow().confirm(MiniState.SetupPending) })
    }

    @Test fun unavailableAddressWithWrittenRequestAndLostReplyAllowsOnlyLikelySuccess() {
        addresses = emptyList()
        requestWritten = true
        fake.setupFailure = IOException("Reply lost after body was sent")
        assertEquals(WifiOffOutcome.Success(true), runCall { flow().confirm(MiniState.SetupPending) })
        assertEquals(1, fake.writes)
    }

    @Test fun unavailableAddressAndUnsentWriteCannotClaimLikelySuccess() {
        addresses = emptyList()
        fake.setupFailure = IOException("Connect failed before request body")
        assertEquals(WifiOffOutcome.Failure(WifiOffError.CantReach), runCall { flow().confirm(MiniState.SetupPending) })
        assertEquals(1, fake.writes)
    }

    @Test fun wifiLossAfterUnsentWriteDoesNotClaimLikelySuccessOrRetry() {
        wifiPresent = false
        fake.setupFailure = IOException("Connect failed before request body")
        val subject = flow(WifiOffConnection.Wifi)
        repeat(2) {
            assertEquals(WifiOffOutcome.Failure(WifiOffError.CantReach), runCall { subject.confirm(MiniState.SetupPending) })
        }
        assertEquals(1, fake.writes)
    }

    @Test fun replacementSetupWifiPreventsLikelySuccessEvenAfterSuccessfulReply() {
        // The observation includes any replacement STARLINK handle, not only the original one.
        wifiPresent = true
        assertEquals(WifiOffOutcome.Failure(WifiOffError.CantReach), runCall {
            flow(WifiOffConnection.Wifi).confirm(MiniState.SetupPending)
        })
        assertEquals(1, fake.writes)
    }

    @Test fun verificationReadExposesFreshDishDetails() {
        val observed = runCall { probeWifiOff(fake, "192.168.1.1", true, addresses) }
        assertEquals("mini", observed.dishInfo?.hardwareVersion)
        assertTrue(observed.dishReachable)
    }

    @Test fun anyRouterResponsePreventsClaimingEthernetSuccess() {
        for (reply in listOf(
            HttpResponse(503, emptyMap(), byteArrayOf()),
            HttpResponse(200, mapOf("grpc-status" to "7"), byteArrayOf()),
            HttpResponse(200, mapOf("grpc-status" to "0"), byteArrayOf(0)),
        )) {
            fake.routerReply = reply
            assertEquals(WifiOffOutcome.Failure(WifiOffError.CantReach), runCall { flow().confirm(MiniState.SetupPending) })
        }
        assertEquals(3, fake.writes)
    }

    @Test fun missingDishDoesNotVerifyEthernetEvenWithStarlinkAddress() {
        fake.dishUp = false
        assertEquals(WifiOffOutcome.Failure(WifiOffError.CantReach), runCall { flow().confirm(MiniState.SetupPending) })
        assertEquals(1, fake.writes)
    }

    @Test fun repeatedSuccessfulConfirmationDoesNotSendAgain() {
        val subject = flow()
        repeat(3) {
            assertEquals(WifiOffOutcome.Success(false), runCall { subject.confirm(MiniState.SetupPending) })
        }
        assertEquals(1, fake.writes)
        assertEquals(3, fake.calls.size)
    }

    @Test fun secondConfirmationWhileWriteIsInFlightCannotSendAgain() {
        val subject = flow()
        fake.onSetup = {
            assertEquals(WifiOffOutcome.Failure(WifiOffError.CantReach), runCall {
                subject.confirm(MiniState.SetupPending)
            })
        }
        assertEquals(WifiOffOutcome.Success(false), runCall { subject.confirm(MiniState.SetupPending) })
        assertEquals(1, fake.writes)
    }

    @Test fun cancellationIsPropagatedAndDoesNotAllowAnotherWrite() {
        val cancelled = CancellationException("Screen closed")
        fake.setupFailure = cancelled
        val subject = flow()
        assertSame(cancelled, assertThrows(CancellationException::class.java) {
            runCall { subject.confirm(MiniState.SetupPending) }
        })
        assertEquals(WifiOffOutcome.Failure(WifiOffError.CantReach), runCall { subject.confirm(MiniState.SetupPending) })
        assertEquals(1, fake.writes)
    }

    @Test fun lateVerificationCannotClaimCertainOrLikelySuccess() {
        fake.onSetup = { now += 59_000 }
        beforeProbe = { now += 2_000 }
        wifiPresent = false
        for (connection in WifiOffConnection.entries) {
            assertEquals(connection.name, WifiOffOutcome.Failure(WifiOffError.CantReach), runCall {
                flow(connection).confirm(MiniState.SetupPending)
            })
        }
        assertEquals(2, fake.writes)
        assertEquals(listOf(1_000L, 1_000L), budgets)
    }

    @Test fun absentNetworkProbeSendsNothing() {
        assertEquals(WifiOffObservation(false, false, false), runCall {
            probeWifiOff(null, "192.168.1.1", false, emptyList())
        })
        assertTrue(fake.calls.isEmpty())
    }

    companion object {
        private fun response(field: Int, value: ByteArray = byteArrayOf()) = HttpResponse(
            200, mapOf("grpc-status" to "0"),
            Frames.request(Wire.Writer().varint(1, 1).bytes(field, value).toByteArray()),
        )

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
