package eu.tsernikov.slminiwifi.data

import java.security.SecureRandom

/** Credentials stay in memory until encrypted by ProfileStore. Never use a data-class printer. */
class Profile(val name: String, val password: String) {
    override fun toString(): String = "Profile([redacted])"
}

/** Provisional WPA2 limits; real Mini limits still need a human hardware test. */
object WifiRules {
    const val MIN_NAME_BYTES = 1
    const val MAX_NAME_BYTES = 32
    const val MIN_PASSWORD_LENGTH = 8
    const val MAX_PASSWORD_LENGTH = 63

    fun isNameValid(name: String): Boolean =
        name.toByteArray(Charsets.UTF_8).size in MIN_NAME_BYTES..MAX_NAME_BYTES &&
            Charsets.UTF_8.newEncoder().canEncode(name)

    fun isPasswordValid(password: String): Boolean =
        password.length in MIN_PASSWORD_LENGTH..MAX_PASSWORD_LENGTH &&
            password.all { it in ' '..'~' }

    fun isValid(profile: Profile): Boolean = isNameValid(profile.name) && isPasswordValid(profile.password)
}

class PasswordGenerator(private val nextInt: (Int) -> Int = SecureRandom()::nextInt) {
    fun password(length: Int = 16): String {
        require(length in 12..WifiRules.MAX_PASSWORD_LENGTH) { "Invalid generated password length" }
        return randomCharacters(length)
    }

    fun name(): String = "Mini-${randomCharacters(4)}"

    private fun randomCharacters(length: Int): String = buildString {
        repeat(length) { append(ALPHABET[nextInt(ALPHABET.length)]) }
    }

    companion object {
        // Exclude 0/O/o, 1/I/i/l/L, 2/Z/z, 5/S/s, 8/B and 6/G.
        const val ALPHABET = "ACDEFHJKMNPQRTUVWXYabcdefghjkmnpqrtuvwxy3479"
    }
}

fun wifiQrPayload(profile: Profile): String {
    fun escape(value: String): String = buildString {
        value.forEach { character ->
            if (character in "\\;,:\"") append('\\')
            append(character)
        }
    }
    return "WIFI:T:WPA;S:${escape(profile.name)};P:${escape(profile.password)};;"
}
