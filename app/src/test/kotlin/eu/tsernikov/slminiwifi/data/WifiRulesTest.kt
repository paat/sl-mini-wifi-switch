package eu.tsernikov.slminiwifi.data

import org.junit.Assert.*
import org.junit.Test

class WifiRulesTest {
    @Test fun nameUsesUtf8ByteLimitsIncludingMultibyteCharacters() {
        assertFalse(WifiRules.isNameValid(""))
        assertTrue(WifiRules.isNameValid("a"))
        assertTrue(WifiRules.isNameValid("a".repeat(32)))
        assertFalse(WifiRules.isNameValid("a".repeat(33)))
        assertTrue(WifiRules.isNameValid("é".repeat(16)))
        assertFalse(WifiRules.isNameValid("é".repeat(17)))
        assertTrue(WifiRules.isNameValid("📡".repeat(8)))
        assertFalse(WifiRules.isNameValid("📡".repeat(9)))
    }

    @Test fun unpairedSurrogatesCannotBecomeAChangedNetworkName() {
        assertFalse(WifiRules.isNameValid("Mini-\uD83D"))
        assertFalse(WifiRules.isNameValid("Mini-\uDC00"))
    }

    @Test fun passwordLengthBoundaries() {
        for (length in listOf(0, 1, 7, 64)) assertFalse(WifiRules.isPasswordValid("a".repeat(length)))
        for (length in listOf(8, 12, 16, 63)) assertTrue(WifiRules.isPasswordValid("a".repeat(length)))
    }

    @Test fun everyPrintableAsciiCharacterIsAllowedAndOtherCharactersAreRejected() {
        for (character in ' '..'~') assertTrue(WifiRules.isPasswordValid(character.toString().repeat(8)))
        for (character in listOf('\u0000', '\n', '\r', '\t', '\u007f', 'é', '中')) {
            assertFalse(WifiRules.isPasswordValid("password$character"))
        }
    }

    @Test fun fullProfileRequiresBothValidFields() {
        assertTrue(WifiRules.isValid(Profile("Mini", "password")))
        assertFalse(WifiRules.isValid(Profile("", "password")))
        assertFalse(WifiRules.isValid(Profile("Mini", "")))
    }

    @Test fun credentialPrinterRedactsBothFields() {
        val profile = Profile("Private network", "secret-password")
        assertEquals("Profile([redacted])", profile.toString())
    }
}
