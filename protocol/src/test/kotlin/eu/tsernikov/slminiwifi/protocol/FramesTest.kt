package eu.tsernikov.slminiwifi.protocol

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class FramesTest {
    @Test fun requestUsesBigEndianLengthAndZeroFlag() {
        val payload = ByteArray(258) { it.toByte() }
        val framed = Frames.request(payload)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 2), framed.copyOf(5))
        assertArrayEquals(payload, Frames.parse(framed).firstDataPayload)
    }

    @Test fun everyVectorParsesIncludingRefusedWriteInput() {
        val files = File("tools/vectors").listFiles { file -> file.extension == "hex" }!!.sortedBy { it.name }
        assertEquals(8, files.size)
        for (file in files) {
            val parsed = Frames.parse(vector(file.name))
            assertEquals(file.name, 1, parsed.dataFrames.size)
            val reader = Wire.Reader(parsed.firstDataPayload!!)
            while (reader.nextField() != null) reader.skip()
        }
    }

    @Test fun capturedResponseHasSuccessTrailerAndOriginalPayload() {
        val parsed = Frames.parse(vector("wifi_setup.response_ok.hex"))
        assertEquals(0, parsed.statusCode)
        assertNull(parsed.message)
        assertArrayEquals(byteArrayOf(8, 4, 24, 0x84.toByte(), 1, 0xda.toByte(), 0xbb.toByte(), 1, 0), parsed.firstDataPayload)
    }

    @Test fun headerOnlyErrorsAreCaseInsensitive() {
        for ((code, message) in listOf(9 to "Wifi has already been set up", 7 to "Permission denied")) {
            val parsed = Frames.parse(byteArrayOf(), mapOf("gRpC-sTaTuS" to "$code", "Grpc-Message" to message))
            assertEquals(code, parsed.statusCode)
            assertEquals(message, parsed.message)
            assertTrue(parsed.dataFrames.isEmpty())
            assertNull(parsed.firstDataPayload)
        }
    }

    @Test fun trailerStatusOverridesHeaderStatusAndMessage() {
        val parsed = Frames.parse(trailers("grpc-status: 0\r\n"), mapOf("Grpc-Status" to "7", "Grpc-Message" to "old error"))
        assertEquals(0, parsed.statusCode)
        assertNull(parsed.message)
    }

    @Test fun percentEncodedMessagesDecodeWithoutChangingPlus() {
        val encoded = "Wifi%20%2B+%20%C3%A4"
        val header = Frames.parse(byteArrayOf(), mapOf("grpc-status" to "9", "grpc-message" to encoded))
        val trailer = Frames.parse(trailers("grpc-status: 9\r\ngrpc-message: $encoded\r\n"))
        assertEquals("Wifi ++ ä", header.message)
        assertEquals(header.message, trailer.message)
    }

    @Test fun multipleDataFramesPreserveOrderAndFirstPayload() {
        val parsed = Frames.parse(Frames.request(byteArrayOf(1)) + Frames.request(byteArrayOf(2)) + trailers("grpc-status: 0\r\n"))
        assertEquals(2, parsed.dataFrames.size)
        assertArrayEquals(byteArrayOf(1), parsed.firstDataPayload)
        assertArrayEquals(byteArrayOf(2), parsed.dataFrames[1])
    }

    @Test fun missingStatusIsRepresentedAsAbsent() {
        assertNull(Frames.parse(byteArrayOf()).statusCode)
        assertNull(Frames.parse(Frames.request(byteArrayOf())).statusCode)
    }

    @Test fun truncatedFramesAndOversizedUnsignedLengthsAreTypedErrors() {
        for (body in listOf(byteArrayOf(0), byteArrayOf(0, 0, 0, 0), byteArrayOf(0, 0, 0, 0, 2, 1), byteArrayOf(0, -1, -1, -1, -1))) {
            assertThrows(ProtocolException::class.java) { Frames.parse(body) }
        }
    }

    @Test fun compressedAndUnknownFrameFlagsAreTypedErrors() {
        for (flag in listOf(1, 2, 0x81, 0xff)) {
            assertThrows(ProtocolException::class.java) { Frames.parse(byteArrayOf(flag.toByte(), 0, 0, 0, 0)) }
        }
    }

    @Test fun malformedTrailersAndStatusHeadersAreTypedErrors() {
        for (text in listOf("broken", "grpc-status: bad\r\n", "grpc-status: -1\r\n", "grpc-status: 999999999999\r\n", "grpc-message: missing status\r\n", "grpc-status: 0\ngrpc-message: x", "grpc-status: 0\r\ngrpc-status: 7\r\n", "grpc-status: 7\r\ngrpc-message: %x\r\n", "grpc-status: 0\r\ngrpc-message: ä\r\n")) {
            assertThrows(ProtocolException::class.java) { Frames.parse(trailers(text)) }
        }
        assertThrows(ProtocolException::class.java) { Frames.parse(byteArrayOf(), mapOf("grpc-status" to "bad")) }
    }

    @Test fun framesAfterTrailersAreTypedErrors() {
        val trailer = trailers("grpc-status: 0\r\n")
        assertThrows(ProtocolException::class.java) { Frames.parse(trailer + Frames.request(byteArrayOf())) }
        assertThrows(ProtocolException::class.java) { Frames.parse(trailer + trailer) }
    }

    private fun trailers(text: String): ByteArray = Frames.request(text.toByteArray(Charsets.UTF_8)).also { it[0] = 0x80.toByte() }
}
