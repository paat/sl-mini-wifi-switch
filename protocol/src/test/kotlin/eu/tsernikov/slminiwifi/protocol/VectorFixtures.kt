package eu.tsernikov.slminiwifi.protocol

import java.io.File

internal fun vector(name: String): ByteArray =
    File("tools/vectors", name).readText().trim().split(Regex("\\s+"))
        .map { it.toInt(16).toByte() }.toByteArray()
