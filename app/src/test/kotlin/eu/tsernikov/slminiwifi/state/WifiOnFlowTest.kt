package eu.tsernikov.slminiwifi.state

import eu.tsernikov.slminiwifi.data.Profile
import eu.tsernikov.slminiwifi.protocol.Frames
import eu.tsernikov.slminiwifi.protocol.HttpResponse
import eu.tsernikov.slminiwifi.protocol.MiniClient
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

class WifiOnFlowTest {
    private var now = 0L
    private val profile = Profile("Mini-field", "abcdefgh12345678")
    private var setupReply = response(3003)
    private var setupFailure: Exception? = null
    private var onSetup: () -> Unit = {}
    private var readReply: () -> HttpResponse = { config() }
    private val calls = mutableListOf<ByteArray>()
    private val budgets = mutableListOf<Long>()
    private val waits = mutableListOf<Long>()
    private val transport = object : Transport {
        override suspend fun post(url: String, headers: Map<String, String>, body: ByteArray): HttpResponse {
            check(url.startsWith("http://192.168.1.1:9001/"))
            calls += body
            if (body.contentEquals(Requests.setupWifi(profile.name, profile.password))) {
                onSetup()
                setupFailure?.let { throw it }
                return setupReply
            }
            // Any name-only, empty-password, bypass or undocumented write fails this fake.
            check(body.contentEquals(Requests.wifiGetConfig())) { "Unexpected request" }
            return readReply()
        }
    }
    private val client = MiniClient(transport, "http://192.168.1.1:9001")
    private val writes get() = calls.count { it.contentEquals(Requests.setupWifi(profile.name, profile.password)) }
    private val reads get() = calls.count { it.contentEquals(Requests.wifiGetConfig()) }

    private fun flow() = WifiOnFlow(
        client,
        observe = { budget -> budgets += budget; client.wifiGetConfig() },
        wait = { waits += it; now += it },
        nowMillis = { now },
    )

    @Test fun noSendBeforeConfirmationAndNoRequestsForNonPendingStates() {
        val subject = flow()
        assertTrue(calls.isEmpty())
        for (state in MiniState.entries.filter { it != MiniState.SetupPending }) {
            assertTrue(runCall { subject.confirm(state, profile) } is WifiOnOutcome.Failure)
        }
        assertTrue(calls.isEmpty())
    }

    @Test fun neverSendsEmptyShortOrInvalidPasswordOrInvalidName() {
        val subject = flow()
        for (invalid in listOf(
            Profile("Mini", ""), Profile("Mini", "short"), Profile("Mini", "passwordé"),
            Profile("Mini", "a".repeat(64)), Profile("", "abcdefgh"), Profile("a".repeat(33), "abcdefgh"),
        )) {
            assertEquals(WifiOnOutcome.Failure(WifiOffError.Refused), runCall { subject.confirm(MiniState.SetupPending, invalid) })
        }
        assertTrue(calls.isEmpty())
    }

    @Test fun sendsExactNameAndNonNullPasswordOnceAndVerifiesBothBands() {
        val subject = flow()
        repeat(3) { assertEquals(WifiOnOutcome.Success, runCall { subject.confirm(MiniState.SetupPending, profile) }) }
        assertEquals(1, writes)
        assertEquals(1, reads)
        assertEquals(listOf(180_000L), budgets)
    }

    @Test fun successAfterTwoMinutesWithoutAnswerIncludesLostWriteReply() {
        setupFailure = IOException("Reply lost")
        readReply = { if (now < 120_000) throw IOException("Router restarting") else config() }
        assertEquals(WifiOnOutcome.Success, runCall { flow().confirm(MiniState.SetupPending, profile) })
        assertEquals(120_000L, now)
        assertEquals(1, writes)
        assertEquals(41, reads)
        assertTrue(waits.all { it == 3_000L })
        assertEquals(60_000L, budgets.last())
    }

    @Test fun anyOpenBandKeepsPollingEvenWhenSetupComplete() {
        readReply = { config(authFields = if (now < 9_000) listOf(2004, 2001) else listOf(2004, 2004)) }
        assertEquals(WifiOnOutcome.Success, runCall { flow().confirm(MiniState.SetupPending, profile) })
        assertEquals(9_000L, now)
        assertEquals(4, reads)
        assertEquals(1, writes)
    }

    @Test fun setupMustBeCompleteBeforeSuccess() {
        readReply = { config(complete = now >= 6_000) }
        assertEquals(WifiOnOutcome.Success, runCall { flow().confirm(MiniState.SetupPending, profile) })
        assertEquals(6_000L, now)
    }

    @Test fun missingAuthOrNoBandsCannotClaimSecureWifi() {
        for (fields in listOf(emptyList(), listOf(0), listOf(2004, 0))) {
            readReply = { config(authFields = fields) }
            assertEquals(WifiOnOutcome.Failure(WifiOffError.CantReach), runCall { flow().confirm(MiniState.SetupPending, profile) })
        }
        assertEquals(3, writes)
        assertEquals(540_000L, now)
    }

    @Test fun allDocumentedProtectedAuthKindsAreAccepted() {
        readReply = { config(authFields = listOf(2002, 2003, 2004)) }
        assertEquals(WifiOnOutcome.Success, runCall { flow().confirm(MiniState.SetupPending, profile) })
    }

    @Test fun maskedReadbackPasswordIsNeverComparedWithSuppliedPassword() {
        readReply = { config(maskedPassword = "•••••") }
        assertEquals(WifiOnOutcome.Success, runCall { flow().confirm(MiniState.SetupPending, profile) })
        assertEquals(1, reads)
    }

    @Test fun timeoutDoesNotRetryWriteOrClaimSuccess() {
        readReply = { config(authFields = listOf(2001, 2001)) }
        val subject = flow()
        repeat(2) {
            assertEquals(WifiOnOutcome.Failure(WifiOffError.CantReach), runCall { subject.confirm(MiniState.SetupPending, profile) })
        }
        assertEquals(180_000L, now)
        assertEquals(1, writes)
        assertEquals(60, reads)
        assertTrue(budgets.all { it in 1..180_000 })
    }

    @Test fun unreachableNetworkWaitsThroughRebootWindowWithoutRetrying() {
        setupFailure = IOException("No route")
        readReply = { throw IOException("No route") }
        assertEquals(WifiOnOutcome.Failure(WifiOffError.CantReach), runCall { flow().confirm(MiniState.SetupPending, profile) })
        assertEquals(180_000L, now)
        assertEquals(1, writes)
        assertEquals(60, reads)
    }

    @Test fun codeNineRefusesWithoutReadOrRetry() {
        setupReply = HttpResponse(200, mapOf("grpc-status" to "9"), byteArrayOf())
        val subject = flow()
        repeat(2) {
            assertEquals(WifiOnOutcome.Failure(WifiOffError.AlreadySetUp), runCall { subject.confirm(MiniState.SetupPending, profile) })
        }
        assertEquals(1, writes)
        assertEquals(0, reads)
    }

    @Test fun codeSevenRefusesWithoutReadOrRetry() {
        setupReply = HttpResponse(200, mapOf("grpc-status" to "7"), byteArrayOf())
        val subject = flow()
        repeat(2) {
            assertEquals(WifiOnOutcome.Failure(WifiOffError.Refused), runCall { subject.confirm(MiniState.SetupPending, profile) })
        }
        assertEquals(1, writes)
        assertEquals(0, reads)
    }

    @Test fun deviceRefusalWhileReadingUsesSameErrorMapping() {
        readReply = { HttpResponse(200, mapOf("grpc-status" to "7"), byteArrayOf()) }
        assertEquals(WifiOnOutcome.Failure(WifiOffError.Refused), runCall { flow().confirm(MiniState.SetupPending, profile) })
        assertEquals(1, writes)
        assertEquals(1, reads)
    }

    @Test fun secondConfirmationDuringWriteCannotSendAgain() {
        val subject = flow()
        onSetup = {
            assertEquals(WifiOnOutcome.Failure(WifiOffError.CantReach), runCall { subject.confirm(MiniState.SetupPending, profile) })
        }
        assertEquals(WifiOnOutcome.Success, runCall { subject.confirm(MiniState.SetupPending, profile) })
        assertEquals(1, writes)
    }

    @Test fun cancellationPropagatesAndCannotResend() {
        val cancelled = CancellationException("Screen closed")
        setupFailure = cancelled
        val subject = flow()
        assertSame(cancelled, assertThrows(CancellationException::class.java) {
            runCall { subject.confirm(MiniState.SetupPending, profile) }
        })
        assertEquals(WifiOnOutcome.Failure(WifiOffError.CantReach), runCall { subject.confirm(MiniState.SetupPending, profile) })
        assertEquals(1, writes)
    }

    @Test fun writeTimeCountsTowardTimeoutAndLateObservationCannotClaimSuccess() {
        onSetup = { now += 179_000 }
        readReply = { now += 2_000; config() }
        assertEquals(WifiOnOutcome.Failure(WifiOffError.CantReach), runCall { flow().confirm(MiniState.SetupPending, profile) })
        assertEquals(listOf(1_000L), budgets)
        assertTrue(waits.isEmpty())
    }

    companion object {
        private fun config(
            complete: Boolean = true,
            authFields: List<Int> = listOf(2004, 2004),
            maskedPassword: String = "•••••",
        ): HttpResponse {
            val network = Wire.Writer()
            for (auth in authFields) {
                val bss = Wire.Writer().string(1005, "Mini-field")
                if (auth != 0) bss.bytes(auth, if (auth == 2002 || auth == 2004) {
                    Wire.Writer().string(1, maskedPassword).toByteArray()
                } else byteArrayOf())
                network.bytes(1007, bss.toByteArray())
            }
            val config = Wire.Writer().varint(7, if (complete) 1 else 0).bytes(1100, network.toByteArray())
            return response(3009, Wire.Writer().bytes(1, config.toByteArray()).toByteArray())
        }

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
