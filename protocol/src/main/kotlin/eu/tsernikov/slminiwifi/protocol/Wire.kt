package eu.tsernikov.slminiwifi.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Malformed or unsupported protobuf / gRPC-web input. Never contains payload data. */
class ProtocolException(message: String) : Exception(message)

/** The small protobuf wire subset used by the Mini. Long values preserve all uint64 bits. */
object Wire {
    class Writer {
        private val output = ByteArrayOutputStream()

        fun varint(field: Int, value: Long): Writer = apply {
            tag(field, 0)
            rawVarint(value)
        }

        fun bytes(field: Int, value: ByteArray): Writer = apply {
            tag(field, 2)
            rawVarint(value.size.toLong())
            output.write(value)
        }

        fun string(field: Int, value: String): Writer = bytes(field, value.toByteArray(Charsets.UTF_8))

        fun toByteArray(): ByteArray = output.toByteArray()

        private fun tag(field: Int, type: Int) {
            require(field in 1..0x1fffffff) { "Invalid protobuf field number" }
            rawVarint((field.toLong() shl 3) or type.toLong())
        }

        private fun rawVarint(value: Long) {
            var remaining = value
            while (remaining and -128L != 0L) {
                output.write((remaining.toInt() and 0x7f) or 0x80)
                remaining = remaining ushr 7
            }
            output.write(remaining.toInt())
        }
    }

    class Reader(private val input: ByteArray) {
        private var position = 0
        private var pending = false
        var wireType: Int = -1
            private set

        fun nextField(): Int? {
            if (pending) throw ProtocolException("Previous protobuf field has not been consumed")
            if (position == input.size) return null
            val tag = rawVarint()
            if (tag <= 0 || tag > 0xffffffffL || tag ushr 3 == 0L) {
                throw ProtocolException("Invalid protobuf tag")
            }
            wireType = (tag and 7).toInt()
            if (wireType !in listOf(0, 1, 2, 5)) {
                throw ProtocolException("Unsupported protobuf wire type")
            }
            pending = true
            return (tag ushr 3).toInt()
        }

        fun varint(): Long {
            expect(0)
            val value = rawVarint()
            pending = false
            return value
        }

        fun bytes(): ByteArray {
            expect(2)
            val size = length()
            val value = input.copyOfRange(position, position + size)
            position += size
            pending = false
            return value
        }

        fun string(): String {
            val value = bytes()
            return try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(value)).toString()
            } catch (_: CharacterCodingException) {
                throw ProtocolException("Invalid protobuf UTF-8 string")
            }
        }

        fun skip() {
            if (!pending) throw ProtocolException("No protobuf field to skip")
            when (wireType) {
                0 -> rawVarint()
                1 -> advance(8)
                2 -> advance(length())
                5 -> advance(4)
            }
            pending = false
        }

        private fun expect(type: Int) {
            if (!pending || wireType != type) throw ProtocolException("Unexpected protobuf wire type")
        }

        private fun length(): Int {
            val size = rawVarint()
            if (size < 0 || size > (input.size - position).toLong()) {
                throw ProtocolException("Truncated protobuf length-delimited field")
            }
            return size.toInt()
        }

        private fun advance(size: Int) {
            if (size > input.size - position) throw ProtocolException("Truncated protobuf fixed-width field")
            position += size
        }

        private fun rawVarint(): Long {
            var value = 0L
            for (index in 0..9) {
                if (position == input.size) throw ProtocolException("Truncated protobuf varint")
                val byte = input[position++].toInt() and 0xff
                if (index == 9 && byte > 1) throw ProtocolException("Protobuf varint exceeds 64 bits")
                value = value or ((byte and 0x7f).toLong() shl (index * 7))
                if (byte and 0x80 == 0) return value
            }
            throw ProtocolException("Protobuf varint exceeds 64 bits")
        }
    }
}
