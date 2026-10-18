package eu.tsernikov.slminiwifi.protocol

import java.io.IOException
import java.util.concurrent.CancellationException
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.*
import org.junit.Test

class MiniClientTest {
    private class FakeTransport(var response: HttpResponse) : Transport {
        var url: String? = null
        var headers: Map<String, String>? = null
        var body: ByteArray? = null
        var calls = 0
        override suspend fun post(url: String, headers: Map<String, String>, body: ByteArray): HttpResponse {
            calls++
            this.url = url
            this.headers = headers
            this.body = body
            return response
        }
    }

    // Fake transports complete inline, so coroutine libraries are unnecessary for these tests.
    private fun <T> runCall(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) { outcome = result }
        })
        return checkNotNull(outcome) { "Fake transport unexpectedly suspended" }.getOrThrow()
    }

    private fun fake(body: ByteArray = byteArrayOf(), code: String = "0") =
        FakeTransport(HttpResponse(200, mapOf("Grpc-Status" to code), body))

    private fun payload(field: Int, nested: ByteArray = byteArrayOf(), id: Long = 1) =
        Frames.request(Wire.Writer().varint(1, id).bytes(field, nested).toByteArray())

    @Test fun capturedSetupSuccessAndHttpContract() {
        val transport = FakeTransport(HttpResponse(200, emptyMap(), vector("wifi_setup.response_ok.hex")))
        val result = runCall { MiniClient(transport, "http://192.168.1.1:9001/").setupBypass(id = 4) }
        assertTrue(result is ClientResult.Ok)
        assertEquals(4L, (result as ClientResult.Ok).response.id)
        assertEquals(132L, result.response.apiVersion)
        assertEquals("http://192.168.1.1:9001/SpaceX.API.Device.Device/Handle", transport.url)
        assertEquals(mapOf("Content-Type" to "application/grpc-web+proto", "X-Grpc-Web" to "1"), transport.headers)
        assertArrayEquals(Requests.setupBypass(4), transport.body)
        assertEquals(1, transport.calls)
    }

    @Test fun allClientMethodsSendTheirRequestAndParseExpectedResponse() {
        val transport = fake()
        val client = MiniClient(transport, "http://192.168.1.1:9001")
        val calls = listOf<Triple<ByteArray, ByteArray, suspend () -> ClientResult>>(
            Triple(Requests.getDeviceInfo(), payload(1004, Wire.Writer().bytes(1, byteArrayOf()).toByteArray())) { client.getDeviceInfo() },
            Triple(Requests.wifiGetConfig(), payload(3009, Wire.Writer().bytes(1, byteArrayOf()).toByteArray())) { client.wifiGetConfig() },
            Triple(Requests.getStatus(), payload(3004)) { client.getStatus() },
            Triple(Requests.setupBypass(), payload(3003)) { client.setupBypass() },
            Triple(Requests.setupWifi("ExampleNet", "example-pass-123"), payload(3003)) { client.setupWifi("ExampleNet", "example-pass-123") },
        )
        for ((request, response, call) in calls) {
            transport.response = HttpResponse(200, mapOf("grpc-status" to "0"), response)
            assertTrue(runCall(call) is ClientResult.Ok)
            assertArrayEquals(request, transport.body)
        }
        assertEquals(5, transport.calls)
    }

    @Test fun trailersOnlyErrorsAreDeviceErrors() {
        for ((code, message) in listOf(9 to "Wifi has already been set up", 7 to "Permission denied")) {
            val transport = FakeTransport(HttpResponse(200, mapOf("gRpC-StAtUs" to "$code", "GRPC-MESSAGE" to message), byteArrayOf()))
            assertEquals(ClientResult.DeviceError(code, message), runCall { MiniClient(transport, "http://192.168.1.1:9001").setupBypass() })
        }
    }

    @Test fun trailerErrorIsHonouredWithoutParsingData() {
        val trailers = Frames.request("grpc-status: 9\r\ngrpc-message: Wifi%20has%20already%20been%20set%20up\r\n".toByteArray()).also { it[0] = 0x80.toByte() }
        val transport = fake(Frames.request(byteArrayOf(0)) + trailers)
        assertEquals(ClientResult.DeviceError(9, "Wifi has already been set up"), runCall { MiniClient(transport, "http://192.168.1.1:9001").setupBypass() })
    }

    @Test fun embeddedDeviceStatusIsHonoured() {
        val status = Wire.Writer().varint(1, 7).string(2, "Permission denied").toByteArray()
        val transport = fake(Frames.request(Wire.Writer().bytes(2, status).toByteArray()))
        assertEquals(ClientResult.DeviceError(7, "Permission denied"), runCall { MiniClient(transport, "http://192.168.1.1:9001").setupBypass() })
    }

    @Test fun malformedAndIncompleteSuccessesAreTypedTransportErrors() {
        val responses = listOf(
            HttpResponse(200, emptyMap(), payload(3003)),
            HttpResponse(200, mapOf("grpc-status" to "0"), byteArrayOf()),
            HttpResponse(200, mapOf("grpc-status" to "0"), byteArrayOf(0, 0)),
            HttpResponse(200, mapOf("grpc-status" to "0"), Frames.request(byteArrayOf(0))),
            HttpResponse(200, mapOf("grpc-status" to "0"), payload(3003, id = 99)),
            HttpResponse(200, mapOf("grpc-status" to "0"), payload(3004)),
        )
        for (response in responses) {
            val result = runCall { MiniClient(FakeTransport(response), "http://192.168.1.1:9001").setupBypass() }
            assertTrue(result is ClientResult.TransportError)
            assertTrue((result as ClientResult.TransportError).cause is ProtocolException)
        }
    }

    @Test fun httpFailureIsTransportError() {
        val result = runCall { MiniClient(FakeTransport(HttpResponse(503, emptyMap(), byteArrayOf())), "http://192.168.1.1:9001").getStatus() }
        assertTrue((result as ClientResult.TransportError).cause is IOException)
    }

    @Test fun transportExceptionIsPreservedWithoutRetry() {
        val failure = IOException("No route")
        var calls = 0
        val transport = object : Transport {
            override suspend fun post(url: String, headers: Map<String, String>, body: ByteArray): HttpResponse {
                calls++
                throw failure
            }
        }
        val result = runCall { MiniClient(transport, "http://192.168.1.1:9001").getStatus() }
        assertSame(failure, (result as ClientResult.TransportError).cause)
        assertEquals(1, calls)
    }

    @Test fun cancellationIsPropagated() {
        val cancelled = CancellationException("Cancelled")
        val transport = object : Transport {
            override suspend fun post(url: String, headers: Map<String, String>, body: ByteArray): HttpResponse = throw cancelled
        }
        assertSame(cancelled, assertThrows(CancellationException::class.java) {
            runCall { MiniClient(transport, "http://192.168.1.1:9001").getStatus() }
        })
    }
}
