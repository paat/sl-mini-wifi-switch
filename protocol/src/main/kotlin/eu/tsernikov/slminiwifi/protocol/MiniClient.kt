package eu.tsernikov.slminiwifi.protocol

import java.io.IOException
import java.util.concurrent.CancellationException

/** Implementations bind HTTP to the selected local Starlink network and enforce timeouts. */
interface Transport {
    suspend fun post(url: String, headers: Map<String, String>, body: ByteArray): HttpResponse
}

data class HttpResponse(val statusCode: Int, val headers: Map<String, String>, val body: ByteArray)

sealed interface ClientResult {
    data class Ok(val response: Response) : ClientResult
    data class DeviceError(val code: Int, val message: String) : ClientResult
    data class TransportError(val cause: Exception) : ClientResult
}

/** No networking implementation or retries: each call sends exactly one request via [transport]. */
class MiniClient(private val transport: Transport, baseUrl: String) {
    private val url = baseUrl.trimEnd('/') + "/SpaceX.API.Device.Device/Handle"

    suspend fun getDeviceInfo(id: Long = 1): ClientResult =
        call(Requests.getDeviceInfo(id), id) { it.deviceInfo != null }

    suspend fun wifiGetConfig(id: Long = 1): ClientResult =
        call(Requests.wifiGetConfig(id), id) { it.wifiGetConfig != null }

    suspend fun getStatus(id: Long = 1): ClientResult =
        call(Requests.getStatus(id), id) { it.wifiGetStatus != null }

    /** Caller must show the consequences and obtain an explicit confirmation tap first. */
    suspend fun setupBypass(id: Long = 1): ClientResult =
        call(Requests.setupBypass(id), id) { it.wifiSetup }

    /** Caller must obtain explicit confirmation before changing the Mini's Wi-Fi. */
    suspend fun setupWifi(name: String, password: String? = null, id: Long = 1): ClientResult =
        call(Requests.setupWifi(name, password, id), id) { it.wifiSetup }

    private suspend fun call(
        request: ByteArray,
        id: Long,
        expectedResponse: (Response) -> Boolean,
    ): ClientResult = try {
        val http = transport.post(
            url,
            mapOf("Content-Type" to "application/grpc-web+proto", "X-Grpc-Web" to "1"),
            request,
        )
        if (http.statusCode !in 200..299) throw IOException("HTTP status ${http.statusCode}")
        val frames = Frames.parse(http.body, http.headers)
        val code = frames.statusCode ?: throw ProtocolException("Missing gRPC status")
        if (code != 0) {
            ClientResult.DeviceError(code, frames.message.orEmpty())
        } else {
            val payload = frames.firstDataPayload ?: throw ProtocolException("Missing response payload")
            val response = Responses.parse(payload)
            if (response.status.code != 0) {
                ClientResult.DeviceError(response.status.code, response.status.message)
            } else {
                if (response.id != id) throw ProtocolException("Response ID does not match request")
                if (!expectedResponse(response)) throw ProtocolException("Unexpected response kind")
                ClientResult.Ok(response)
            }
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        ClientResult.TransportError(failure)
    }
}
