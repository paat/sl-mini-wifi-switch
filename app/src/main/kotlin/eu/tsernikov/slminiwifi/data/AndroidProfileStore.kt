package eu.tsernikov.slminiwifi.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** One private encrypted blob; Android Keystore owns the non-exportable AES key. */
fun createProfileStore(context: Context): ProfileStore {
    val preferences = context.getSharedPreferences("wifi_profile", Context.MODE_PRIVATE)
    return ProfileStore(KeystoreProfileCipher(), object : ProfileBlob {
        override fun read(): ByteArray? = preferences.getString("encrypted_profile", null)?.let {
            Base64.decode(it, Base64.NO_WRAP)
        }

        override fun write(ciphertext: ByteArray) {
            if (!preferences.edit().putString("encrypted_profile", Base64.encodeToString(ciphertext, Base64.NO_WRAP)).commit()) {
                throw ProfileStorageException()
            }
        }
    })
}

class KeystoreProfileCipher : ProfileCipher {
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build())
        }.generateKey()
    }

    override fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        check(cipher.iv.size == IV_BYTES) { "Invalid encryption parameters" }
        return byteArrayOf(VERSION) + cipher.iv + cipher.doFinal(plaintext)
    }

    override fun decrypt(ciphertext: ByteArray): ByteArray {
        require(ciphertext.size >= 1 + IV_BYTES + 16 && ciphertext[0] == VERSION) { "Invalid encrypted profile" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, ciphertext.copyOfRange(1, 1 + IV_BYTES)))
        return cipher.doFinal(ciphertext, 1 + IV_BYTES, ciphertext.size - 1 - IV_BYTES)
    }

    companion object {
        private const val KEY_ALIAS = "sl-mini-wifi-profile-v1"
        private const val VERSION: Byte = 1
        private const val IV_BYTES = 12
    }
}
