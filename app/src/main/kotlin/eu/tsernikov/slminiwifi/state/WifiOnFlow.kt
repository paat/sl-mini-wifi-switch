package eu.tsernikov.slminiwifi.state

import eu.tsernikov.slminiwifi.data.Profile
import eu.tsernikov.slminiwifi.data.WifiRules
import eu.tsernikov.slminiwifi.protocol.AuthKind
import eu.tsernikov.slminiwifi.protocol.ClientResult
import eu.tsernikov.slminiwifi.protocol.MiniClient
import kotlinx.coroutines.delay

sealed interface WifiOnOutcome {
    data object Success : WifiOnOutcome
    data class Failure(val error: WifiOffError) : WifiOnOutcome
}

/** One explicit confirmation, one write. Only the read-only observation repeats. */
class WifiOnFlow(
    private val client: MiniClient,
    private val observe: suspend (remainingMillis: Long) -> ClientResult,
    private val wait: suspend (Long) -> Unit = { delay(it) },
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private var attempted = false
    private var outcome: WifiOnOutcome? = null

    suspend fun confirm(state: MiniState, profile: Profile): WifiOnOutcome {
        outcome?.let { return it }
        if (attempted) return WifiOnOutcome.Failure(WifiOffError.CantReach)
        if (state != MiniState.SetupPending) {
            return WifiOnOutcome.Failure(
                if (state == MiniState.Configured || state == MiniState.Bypass) WifiOffError.AlreadySetUp
                else WifiOffError.CantReach,
            )
        }
        if (!WifiRules.isValid(profile)) return WifiOnOutcome.Failure(WifiOffError.Refused)
        // Before any suspension: a second tap or cancellation can never duplicate the write.
        attempted = true
        val startedAt = nowMillis()
        val reply = client.setupWifi(profile.name, profile.password)
        if (reply is ClientResult.DeviceError) return finish(failure(reply))

        // The router can restart before its write reply arrives. Silence does not justify retrying.
        while (nowMillis() - startedAt < VERIFY_TIMEOUT_MILLIS) {
            val observation = observe(VERIFY_TIMEOUT_MILLIS - (nowMillis() - startedAt))
            if (observation is ClientResult.DeviceError) return finish(failure(observation))
            val config = (observation as? ClientResult.Ok)?.response?.wifiGetConfig
            val serviceSets = config?.networks?.flatMap { it.basicServiceSets }.orEmpty()
            if (nowMillis() - startedAt <= VERIFY_TIMEOUT_MILLIS && config?.setupComplete == true &&
                serviceSets.isNotEmpty() && serviceSets.all {
                    it.authKind == AuthKind.WPA2 || it.authKind == AuthKind.WPA3 || it.authKind == AuthKind.WPA2_WPA3
                }) {
                return finish(WifiOnOutcome.Success)
            }
            val remaining = VERIFY_TIMEOUT_MILLIS - (nowMillis() - startedAt)
            if (remaining > 0) wait(minOf(POLL_INTERVAL_MILLIS, remaining))
        }
        return finish(WifiOnOutcome.Failure(WifiOffError.CantReach))
    }

    private fun failure(reply: ClientResult.DeviceError) = WifiOnOutcome.Failure(
        if (reply.code == 9) WifiOffError.AlreadySetUp else WifiOffError.Refused,
    )

    private fun finish(result: WifiOnOutcome): WifiOnOutcome = result.also { outcome = it }

    companion object {
        const val VERIFY_TIMEOUT_MILLIS = 180_000L
        const val POLL_INTERVAL_MILLIS = 3_000L
    }
}
