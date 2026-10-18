package eu.tsernikov.slminiwifi.data

import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import org.junit.Assert.*
import org.junit.Test

class ProfileStoreTest {
    private class MemoryBlob : ProfileBlob {
        var bytes: ByteArray? = null
        override fun read(): ByteArray? = bytes?.clone()
        override fun write(ciphertext: ByteArray) { bytes = ciphertext.clone() }
    }

    /** Real JVM AES-GCM exercises the injectable boundary without emulating Android Keystore. */
    private class TestCipher : ProfileCipher {
        private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        var lastPlaintext: ByteArray? = null
        override fun encrypt(plaintext: ByteArray): ByteArray {
            lastPlaintext = plaintext
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            return cipher.iv + cipher.doFinal(plaintext)
        }
        override fun decrypt(ciphertext: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, ciphertext.copyOfRange(0, 12)))
            return cipher.doFinal(ciphertext.copyOfRange(12, ciphertext.size)).also { lastPlaintext = it }
        }
    }

    private val blob = MemoryBlob()
    private val cipher = TestCipher()
    private val store = ProfileStore(cipher, blob)
    private val profile = Profile("Mini-é;name", "safe-secret1234;")

    @Test fun emptyStorageHasNoProfile() {
        assertNull(store.load())
    }

    @Test fun roundTripStoresOnlyCiphertextAndClearsTemporaryPlaintext() {
        store.save(profile)
        assertFalse(blob.bytes!!.toString(Charsets.ISO_8859_1).contains(profile.password))
        assertFalse(blob.bytes!!.toString(Charsets.ISO_8859_1).contains(profile.name))
        assertTrue(cipher.lastPlaintext!!.all { it == 0.toByte() })
        val restored = ProfileStore(cipher, blob).load()!!
        assertEquals(profile.name, restored.name)
        assertEquals(profile.password, restored.password)
        assertTrue(cipher.lastPlaintext!!.all { it == 0.toByte() })
    }

    @Test fun savingAgainUsesFreshIvAndReplacesLastProfile() {
        store.save(profile)
        val first = blob.bytes!!.clone()
        store.save(profile)
        assertFalse(first.contentEquals(blob.bytes!!))
        store.save(Profile("Mini-next", "another-password"))
        assertEquals("Mini-next", store.load()!!.name)
        assertEquals("another-password", store.load()!!.password)
    }

    @Test fun ciphertextTamperingFailsWithoutLeakingCredentials() {
        store.save(profile)
        blob.bytes!![15] = (blob.bytes!![15].toInt() xor 1).toByte()
        val error = assertThrows(ProfileStorageException::class.java) { store.load() }
        assertNull(error.cause)
        assertFalse(error.toString().contains(profile.password))
    }

    @Test fun missingKeystoreKeyCannotRecoverProfile() {
        store.save(profile)
        assertThrows(ProfileStorageException::class.java) { ProfileStore(TestCipher(), blob).load() }
    }

    @Test fun encryptionFailureNeverWritesAndExceptionIsSanitized() {
        val broken = object : ProfileCipher {
            override fun encrypt(plaintext: ByteArray): ByteArray = throw IllegalArgumentException(profile.password)
            override fun decrypt(ciphertext: ByteArray): ByteArray = throw IllegalArgumentException(profile.password)
        }
        val error = assertThrows(ProfileStorageException::class.java) { ProfileStore(broken, blob).save(profile) }
        assertNull(blob.bytes)
        assertNull(error.cause)
        assertFalse(error.toString().contains(profile.password))
    }

    @Test fun invalidProfileNeverWrites() {
        assertThrows(ProfileStorageException::class.java) { store.save(Profile("Mini", "")) }
        assertNull(blob.bytes)
    }

    @Test fun malformedDecryptedProfileIsRejected() {
        blob.write(cipher.encrypt(byteArrayOf(0, 0, 0, 0)))
        assertThrows(ProfileStorageException::class.java) { store.load() }
        assertTrue(cipher.lastPlaintext!!.all { it == 0.toByte() })
    }
}
