package eu.tsernikov.slminiwifi.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

interface ProfileCipher {
    fun encrypt(plaintext: ByteArray): ByteArray
    fun decrypt(ciphertext: ByteArray): ByteArray
}

/** This boundary accepts only the ciphertext produced by ProfileCipher. */
interface ProfileBlob {
    fun read(): ByteArray?
    fun write(ciphertext: ByteArray)
}

/** No underlying exception is retained: cipher or file errors must never expose credentials. */
class ProfileStorageException : Exception("Unable to access saved Wi-Fi")

class ProfileStore(private val cipher: ProfileCipher, private val blob: ProfileBlob) {
    fun save(profile: Profile) {
        if (!WifiRules.isValid(profile)) throw ProfileStorageException()
        val plaintext = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeUTF(profile.name)
                output.writeUTF(profile.password)
            }
            bytes.toByteArray()
        }
        try {
            blob.write(cipher.encrypt(plaintext))
        } catch (_: Exception) {
            throw ProfileStorageException()
        } finally {
            plaintext.fill(0)
        }
    }

    fun load(): Profile? {
        try {
            val ciphertext = blob.read() ?: return null
            val plaintext = cipher.decrypt(ciphertext)
            try {
                return DataInputStream(ByteArrayInputStream(plaintext)).use { input ->
                    Profile(input.readUTF(), input.readUTF()).also {
                        if (input.available() != 0 || !WifiRules.isValid(it)) throw ProfileStorageException()
                    }
                }
            } finally {
                plaintext.fill(0)
            }
        } catch (_: Exception) {
            throw ProfileStorageException()
        }
    }
}
