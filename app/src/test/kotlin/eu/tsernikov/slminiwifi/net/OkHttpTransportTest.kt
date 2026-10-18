package eu.tsernikov.slminiwifi.net

import java.io.IOException
import java.net.Socket
import java.net.UnknownHostException
import javax.net.SocketFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OkHttpTransportTest {
    private val path = "/SpaceX.API.Device.Device/Handle"

    @Test fun nonLocalHostIsRejectedBeforeOpeningSocket() {
        OkHttpTransport(NoSockets, "192.168.1.1").use { transport ->
            assertThrows(UnknownHostException::class.java) {
                runBlocking { transport.post("http://example.com:9001$path", emptyMap(), byteArrayOf()) }
            }
        }
    }

    @Test fun unlistedIpIsRejectedBeforeOpeningSocket() {
        OkHttpTransport(NoSockets, "192.168.2.1").use { transport ->
            assertThrows(UnknownHostException::class.java) {
                runBlocking { transport.post("http://8.8.8.8:9001$path", emptyMap(), byteArrayOf()) }
            }
        }
    }

    @Test fun dnsAcceptsOnlyTheTwoFixedAddressesAndDiscoveredGateway() {
        val dns = LocalAddresses("192.168.2.1")
        listOf("192.168.1.1", "192.168.100.1", "192.168.2.1").forEach {
            assertEquals(it, dns.lookup(it).single().hostAddress)
        }
        listOf("localhost", "192.168.2.2", "127.0.0.1", "::1", "192.168.001.1", "0xc0a80101")
            .forEach { assertThrows(UnknownHostException::class.java) { dns.lookup(it) } }
    }

    @Test fun endpointsRequireDocumentedHttpPortsAndExactHandlePath() {
        val addresses = LocalAddresses("192.168.2.1")
        addresses.checkEndpoint("http://192.168.1.1:9001$path")
        addresses.checkEndpoint("http://192.168.2.1:9001$path")
        addresses.checkEndpoint("http://192.168.100.1:9201$path")
        listOf(
            "https://192.168.1.1:9001$path",
            "http://192.168.1.1:9201$path",
            "http://192.168.100.1:9001$path",
            "http://192.168.1.1:9001/other",
            "http://user@192.168.1.1:9001$path",
            "http://192.168.1.1:9001$path?target=example.com",
            "http://192.168.1.1:9001$path#fragment",
            "http://3232235777:9001$path",
        ).forEach { url -> assertThrows(IOException::class.java) { addresses.checkEndpoint(url) } }
    }

    @Test fun trailingDotAndEncodedHostsAreRejectedBeforeOpeningSocket() {
        OkHttpTransport(NoSockets, "192.168.1.1").use { transport ->
            listOf("192.168.1.1.", "192.168.1.1%2e", "192.168.100.1.").forEach { host ->
                assertThrows(IOException::class.java) {
                    runBlocking { transport.post("http://$host:9001$path", emptyMap(), byteArrayOf()) }
                }
            }
        }
    }

    @Test fun gatewayMustBeACanonicalIpv4Literal() {
        listOf("router.local", "192.168.1.999", "192.168.01.1", "192.168.1", "::1", "-1.1.1.1")
            .forEach { assertThrows(IllegalArgumentException::class.java) { LocalAddresses(it) } }
    }

    private object NoSockets : SocketFactory() {
        override fun createSocket(): Socket = error("A rejected endpoint must not open a socket")
        override fun createSocket(host: String?, port: Int): Socket = createSocket()
        override fun createSocket(host: String?, port: Int, local: java.net.InetAddress?, localPort: Int): Socket = createSocket()
        override fun createSocket(host: java.net.InetAddress?, port: Int): Socket = createSocket()
        override fun createSocket(host: java.net.InetAddress?, port: Int, local: java.net.InetAddress?, localPort: Int): Socket = createSocket()
    }
}
