package eu.tsernikov.slminiwifi.net

import android.net.Network
import eu.tsernikov.slminiwifi.protocol.HttpResponse
import eu.tsernikov.slminiwifi.protocol.Transport
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/** Every socket belongs to the selected link; this client never uses system DNS or a proxy. */
class OkHttpTransport internal constructor(
    socketFactory: SocketFactory,
    routerAddress: String,
    onRequestWritten: () -> Unit = {},
) : Transport, Closeable {
    constructor(network: Network, routerAddress: String, onRequestWritten: () -> Unit = {}) :
        this(network.socketFactory, routerAddress, onRequestWritten)

    private val addresses = LocalAddresses(routerAddress)
    private val client = OkHttpClient.Builder()
        .socketFactory(socketFactory)
        .eventListener(object : EventListener() {
            override fun requestBodyEnd(call: Call, byteCount: Long) = onRequestWritten()
        })
        .dns(addresses)
        .proxy(Proxy.NO_PROXY)
        .protocols(listOf(Protocol.HTTP_1_1))
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .callTimeout(13, TimeUnit.SECONDS)
        .build()

    override suspend fun post(url: String, headers: Map<String, String>, body: ByteArray): HttpResponse {
        // Validate the original spelling before OkHttp can normalize alternative IP spellings.
        addresses.checkEndpoint(url)
        val request = Request.Builder().url(url).post(body.toRequestBody()).apply {
            headers.forEach { (name, value) -> header(name, value) }
        }.build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            HttpResponse(
                                it.code,
                                it.headers.names().associateWith { name -> it.header(name).orEmpty() },
                                it.body?.bytes() ?: byteArrayOf(),
                            )
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (failure: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(failure)
                    }
                }
            })
        }
    }

    override fun close() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }
}

/** Exact IPv4 literals only. getByAddress cannot issue a DNS query. */
internal class LocalAddresses(routerAddress: String) : Dns {
    private val allowed = setOf("192.168.1.1", "192.168.100.1", routerAddress)

    init {
        require(ipv4Bytes(routerAddress) != null) { "The router address must be an IPv4 literal" }
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val bytes = ipv4Bytes(hostname)
        if (hostname !in allowed || bytes == null) throw UnknownHostException("Address is not allowed")
        return listOf(InetAddress.getByAddress(bytes))
    }

    fun checkEndpoint(url: String) {
        val uri = try {
            URI(url)
        } catch (failure: Exception) {
            throw IOException("Invalid local endpoint", failure)
        }
        // Android's URI implementation can normalize a trailing dot in host. The raw
        // authority must contain exactly the canonical literal and documented port.
        val host = uri.rawAuthority?.substringBefore(':')
            ?: throw UnknownHostException("An IPv4 literal is required")
        lookup(host)
        if (uri.rawAuthority != "$host:${uri.port}") throw IOException("Endpoint is not allowed")
        val routerEndpoint = host != "192.168.100.1" && uri.port == 9001
        val dishEndpoint = host == "192.168.100.1" && uri.port == 9201
        if (uri.scheme != "http" || uri.rawUserInfo != null || uri.rawQuery != null ||
            uri.rawFragment != null || uri.rawPath != "/SpaceX.API.Device.Device/Handle" ||
            !(routerEndpoint || dishEndpoint)
        ) throw IOException("Endpoint is not allowed")
    }
}

internal fun ipv4Bytes(value: String): ByteArray? {
    val parts = value.split('.')
    if (parts.size != 4) return null
    val bytes = ByteArray(4)
    parts.forEachIndexed { index, part ->
        if (part.isEmpty() || part.length > 3 || part.any { it !in '0'..'9' }) return null
        if (part.length > 1 && part[0] == '0') return null
        val number = part.toInt()
        if (number !in 0..255) return null
        bytes[index] = number.toByte()
    }
    return bytes
}
