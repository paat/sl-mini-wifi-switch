package eu.tsernikov.slminiwifi.data

import org.junit.Assert.*
import org.junit.Test

class PasswordGeneratorTest {
    @Test fun generatedPasswordsUseReadableLettersAndDigitsAndMeetRules() {
        repeat(100) {
            val password = PasswordGenerator().password()
            assertEquals(16, password.length)
            assertTrue(password.all { it in PasswordGenerator.ALPHABET })
            assertTrue(WifiRules.isPasswordValid(password))
            assertFalse(password.any { it in "0Oo1IilL2Zz5Ss8B6G" })
        }
    }

    @Test fun randomnessSourceIsInjectableAndReceivesAlphabetBoundForEachCharacter() {
        var calls = 0
        val generator = PasswordGenerator { bound ->
            assertEquals(PasswordGenerator.ALPHABET.length, bound)
            calls++ % bound
        }
        assertEquals(PasswordGenerator.ALPHABET.take(16), generator.password())
        assertEquals(16, calls)
    }

    @Test fun minimumAndMaximumGeneratedLengthsAreAccepted() {
        val generator = PasswordGenerator { 0 }
        assertEquals(12, generator.password(12).length)
        assertEquals(63, generator.password(63).length)
        assertThrows(IllegalArgumentException::class.java) { generator.password(11) }
        assertThrows(IllegalArgumentException::class.java) { generator.password(64) }
    }

    @Test fun suggestedNameHasFourRandomReadableCharacters() {
        var calls = 0
        val generator = PasswordGenerator { calls++ }
        assertEquals("Mini-${PasswordGenerator.ALPHABET.take(4)}", generator.name())
        assertEquals(4, calls)
    }
}
