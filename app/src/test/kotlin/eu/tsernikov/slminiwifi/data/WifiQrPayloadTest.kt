package eu.tsernikov.slminiwifi.data

import org.junit.Assert.assertEquals
import org.junit.Test

class WifiQrPayloadTest {
    @Test fun standardPayloadHasWpaTypeAndTerminators() {
        assertEquals("WIFI:T:WPA;S:Mini-field;P:abcdefgh1234;;", wifiQrPayload(Profile("Mini-field", "abcdefgh1234")))
    }

    @Test fun everyReservedCharacterIsEscapedInBothFields() {
        val special = "\\;,:\""
        val escaped = "\\\\\\;\\,\\:\\\""
        assertEquals("WIFI:T:WPA;S:$escaped;P:$escaped;;", wifiQrPayload(Profile(special, special)))
    }

    @Test fun unicodeAndSpacesArePreservedAndEscapesDoNotCreateExtraFields() {
        assertEquals("WIFI:T:WPA;S:Mini café;P:space \\;S\\:evil\\;\\\\;;",
            wifiQrPayload(Profile("Mini café", "space ;S:evil;\\")))
    }
}
