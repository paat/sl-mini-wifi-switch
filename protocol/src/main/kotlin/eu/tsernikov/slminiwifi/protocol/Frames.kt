package eu.tsernikov.slminiwifi.protocol

import java.net.URLDecoder

data class GrpcResponse(
    val dataFrames: List<ByteArray>,
    val statusCode: Int?,
    val message: String?,
) {
    val firstDataPayload: ByteArray? get() = dataFrames.firstOrNull()
}

object Frames {
    fun request(payload: ByteArray): ByteArray {
        val frame = ByteArray(5 + payload.size)
        for (index in 0..3) frame[index + 1] = (payload.size ushr (24 - index * 8)).toByte()
        payload.copyInto(frame, 5)
        return frame
    }

    /** Body trailers take precedence over HTTP status headers; absent status remains null. */
    fun parse(body: ByteArray, headers: Map<String, String> = emptyMap()): GrpcResponse {
        var status = headers.entries.lastOrNull { it.key.equals("grpc-status", ignoreCase = true) }
            ?.value?.let(::parseStatus)
        var message = headers.entries.lastOrNull { it.key.equals("grpc-message", ignoreCase = true) }
            ?.value?.let(::decodeMessage)
        val frames = mutableListOf<ByteArray>()
        var position = 0
        var hadTrailers = false
        while (position < body.size) {
            if (hadTrailers) throw ProtocolException("Unexpected frame after gRPC-web trailers")
            if (body.size - position < 5) throw ProtocolException("Truncated gRPC-web frame header")
            val flags = body[position].toInt() and 0xff
            if (flags != 0 && flags != 0x80) throw ProtocolException("Unsupported gRPC-web frame flags")
            var length = 0L
            for (index in 1..4) length = (length shl 8) or (body[position + index].toLong() and 0xff)
            position += 5
            if (length > (body.size - position).toLong()) throw ProtocolException("Truncated gRPC-web frame payload")
            val payload = body.copyOfRange(position, position + length.toInt())
            position += length.toInt()
            if (flags == 0) {
                frames += payload
            } else {
                hadTrailers = true
                val trailers = parseTrailers(payload)
                status = trailers.first
                message = trailers.second
            }
        }
        return GrpcResponse(frames.toList(), status, message)
    }

    private fun parseTrailers(payload: ByteArray): Pair<Int, String?> {
        if (payload.any { it.toInt() !in 0..127 }) throw ProtocolException("Non-ASCII gRPC-web trailers")
        var status: Int? = null
        var message: String? = null
        for (line in payload.toString(Charsets.US_ASCII).split("\r\n")) {
            if (line.isEmpty()) continue
            val colon = line.indexOf(':')
            if (colon <= 0 || line.any { it == '\r' || it == '\n' }) {
                throw ProtocolException("Malformed gRPC-web trailer line")
            }
            val name = line.substring(0, colon)
            val value = line.substring(colon + 1).trim()
            when {
                name.equals("grpc-status", ignoreCase = true) -> {
                    if (status != null) throw ProtocolException("Duplicate gRPC-web status trailer")
                    status = parseStatus(value)
                }
                name.equals("grpc-message", ignoreCase = true) -> message = decodeMessage(value)
            }
        }
        return (status ?: throw ProtocolException("Missing gRPC-web status trailer")) to message
    }

    private fun parseStatus(value: String): Int {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || trimmed.any { it !in '0'..'9' }) throw ProtocolException("Invalid gRPC status")
        return trimmed.toIntOrNull() ?: throw ProtocolException("Invalid gRPC status")
    }

    private fun decodeMessage(value: String): String = try {
        // gRPC percent encoding is not form encoding: a literal plus must remain a plus.
        URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
    } catch (_: IllegalArgumentException) {
        throw ProtocolException("Invalid gRPC message percent encoding")
    }
}
