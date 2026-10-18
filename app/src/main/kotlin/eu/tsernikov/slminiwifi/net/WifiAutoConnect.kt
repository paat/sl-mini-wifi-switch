package eu.tsernikov.slminiwifi.net

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.provider.Settings

enum class WifiJoinOutcome { SAVED, ALREADY_EXISTS, DECLINED, NOTIFICATION, UNAVAILABLE }

/** Called after explaining Android's Save screen; a saved network is not proof of connection. */
interface WifiAutoConnect {
    /** null means the system Save screen was launched and its result is still pending. */
    fun connect(name: String, password: String): WifiJoinOutcome?
    fun activityResult(resultCode: Int, data: Intent?): WifiJoinOutcome
}

/** Keeps platform calls replaceable without storing credentials in a request object's toString. */
interface WifiJoinPlatform {
    fun releaseRequest()
    fun launchSave(name: String, password: String)
    fun suggest(name: String, password: String): Int
}

class AndroidWifiAutoConnect(
    private val platform: WifiJoinPlatform,
    private val apiLevel: Int,
) : WifiAutoConnect {
    constructor(
        context: Context,
        releaseRequest: () -> Unit,
        launchSave: (Intent) -> Unit,
    ) : this(AndroidWifiJoinPlatform(context, releaseRequest, launchSave), Build.VERSION.SDK_INT)

    override fun connect(name: String, password: String): WifiJoinOutcome? = try {
        // A specifier keeps Wi-Fi app-local. Release it before asking Android to save/join.
        platform.releaseRequest()
        if (apiLevel >= 30) {
            platform.launchSave(name, password)
            null
        } else {
            when (platform.suggest(name, password)) {
                WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS,
                WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE -> WifiJoinOutcome.NOTIFICATION
                else -> WifiJoinOutcome.UNAVAILABLE
            }
        }
    } catch (_: RuntimeException) {
        // Platform exception messages may contain credentials. Never retain or log them.
        WifiJoinOutcome.UNAVAILABLE
    }

    override fun activityResult(resultCode: Int, data: Intent?): WifiJoinOutcome {
        if (resultCode != Activity.RESULT_OK) return WifiJoinOutcome.DECLINED
        val results = data?.getIntegerArrayListExtra(Settings.EXTRA_WIFI_NETWORK_RESULT_LIST)
        return when (results?.singleOrNull()) {
            Settings.ADD_WIFI_RESULT_SUCCESS -> WifiJoinOutcome.SAVED
            Settings.ADD_WIFI_RESULT_ALREADY_EXISTS -> WifiJoinOutcome.ALREADY_EXISTS
            else -> WifiJoinOutcome.UNAVAILABLE
        }
    }
}

private class AndroidWifiJoinPlatform(
    context: Context,
    private val release: () -> Unit,
    private val launch: (Intent) -> Unit,
) : WifiJoinPlatform {
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)

    override fun releaseRequest() = release()

    override fun launchSave(name: String, password: String) {
        launch(Intent(Settings.ACTION_WIFI_ADD_NETWORKS).putParcelableArrayListExtra(
            Settings.EXTRA_WIFI_NETWORK_LIST,
            arrayListOf(suggestion(name, password)),
        ))
    }

    @SuppressLint("MissingPermission") // CHANGE_WIFI_STATE is a manifest permission.
    override fun suggest(name: String, password: String): Int =
        wifi.addNetworkSuggestions(listOf(suggestion(name, password)))

    private fun suggestion(name: String, password: String): WifiNetworkSuggestion =
        WifiNetworkSuggestion.Builder().setSsid(name).setWpa2Passphrase(password).build()
}
