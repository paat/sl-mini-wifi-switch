package eu.tsernikov.slminiwifi.protocol

import org.junit.Assert.*
import org.junit.Test

class WireTest {
    @Test fun varintsRoundTripIncludingUnsigned64BitBoundaries() {
        for (value in listOf(0L, 1L, 127L, 128L, 16383L, 16384L, Long.MAX_VALUE, Long.MIN_VALUE, -1L)) {
            val reader = Wire.Reader(Wire.Writer().varint(1, value).toByteArray())
            assertEquals(1, reader.nextField())
            assertEquals(value, reader.varint())
            assertNull(reader.nextField())
        }
        assertArrayEquals(byteArrayOf(8, 0x80.toByte(), 1), Wire.Writer().varint(1, 128).toByteArray())
    }

    @Test fun lengthDelimitedBytesAndUnicodeRoundTrip() {
        val encoded = Wire.Writer().bytes(2, byteArrayOf(0, -1)).string(3009, "Mägi 📡").bytes(3, byteArrayOf()).toByteArray()
        val reader = Wire.Reader(encoded)
        assertEquals(2, reader.nextField())
        assertArrayEquals(byteArrayOf(0, -1), reader.bytes())
        assertEquals(3009, reader.nextField())
        assertEquals("Mägi 📡", reader.string())
        assertEquals(3, reader.nextField())
        assertArrayEquals(byteArrayOf(), reader.bytes())
        assertNull(reader.nextField())
    }

    @Test fun unknownFieldsOfAllSupportedWireTypesAreSkipped() {
        val fields = Wire.Writer().varint(1, 999).toByteArray() +
            byteArrayOf(0x11, 1, 2, 3, 4, 5, 6, 7, 8) +
            Wire.Writer().bytes(3, byteArrayOf(1, 2, 3)).toByteArray() +
            byteArrayOf(0x25, 1, 2, 3, 4) + Wire.Writer().string(5, "kept").toByteArray()
        val reader = Wire.Reader(fields)
        for (field in 1..4) {
            assertEquals(field, reader.nextField())
            reader.skip()
        }
        assertEquals(5, reader.nextField())
        assertEquals("kept", reader.string())
        assertNull(reader.nextField())
    }

    @Test fun truncatedVarintsAreTypedErrors() {
        assertThrows(ProtocolException::class.java) { Wire.Reader(byteArrayOf(0x80.toByte())).nextField() }
        val reader = Wire.Reader(byteArrayOf(8, 0x80.toByte()))
        reader.nextField()
        assertThrows(ProtocolException::class.java) { reader.varint() }
    }

    @Test fun overflowingVarintsAreTypedErrors() {
        val malformed = byteArrayOf(8) + ByteArray(9) { 0xff.toByte() } + byteArrayOf(2)
        val reader = Wire.Reader(malformed)
        reader.nextField()
        assertThrows(ProtocolException::class.java) { reader.varint() }
    }

    @Test fun invalidTagsAndUnsupportedWireTypesAreTypedErrors() {
        for (bytes in listOf(byteArrayOf(0), byteArrayOf(1), byteArrayOf(0x0b), byteArrayOf(0x0c), byteArrayOf(0x0e), byteArrayOf(0x0f), byteArrayOf(-1, -1, -1, -1, 0x10))) {
            assertThrows(ProtocolException::class.java) { Wire.Reader(bytes).nextField() }
        }
    }

    @Test fun maximumFieldNumberRoundTrips() {
        val reader = Wire.Reader(Wire.Writer().varint(0x1fffffff, 1).toByteArray())
        assertEquals(0x1fffffff, reader.nextField())
        assertEquals(1L, reader.varint())
    }

    @Test fun truncatedLengthDelimitedFieldsAreTypedErrors() {
        for (bytes in listOf(byteArrayOf(10), byteArrayOf(10, 2, 1), byteArrayOf(10) + ByteArray(9) { -1 } + byteArrayOf(1))) {
            val reader = Wire.Reader(bytes)
            reader.nextField()
            assertThrows(ProtocolException::class.java) { reader.bytes() }
        }
    }

    @Test fun truncatedUnknownFieldsAreTypedErrors() {
        for (bytes in listOf(byteArrayOf(8), byteArrayOf(9, 1), byteArrayOf(10, 2, 1), byteArrayOf(13, 1))) {
            val reader = Wire.Reader(bytes)
            reader.nextField()
            assertThrows(ProtocolException::class.java) { reader.skip() }
        }
    }

    @Test fun wrongWireTypeAndUnconsumedFieldsAreTypedErrors() {
        val reader = Wire.Reader(Wire.Writer().bytes(1, byteArrayOf()).toByteArray())
        reader.nextField()
        assertThrows(ProtocolException::class.java) { reader.varint() }
        assertThrows(ProtocolException::class.java) { reader.nextField() }
    }

    @Test fun malformedUtf8IsTypedError() {
        val reader = Wire.Reader(Wire.Writer().bytes(1, byteArrayOf(0xc3.toByte(), 0x28)).toByteArray())
        reader.nextField()
        assertThrows(ProtocolException::class.java) { reader.string() }
    }

    @Test fun encoderRejectsInvalidFieldNumbers() {
        for (field in listOf(0, -1, 0x20000000)) {
            assertThrows(IllegalArgumentException::class.java) { Wire.Writer().varint(field, 0) }
        }
    }
}
