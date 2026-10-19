package eu.tsernikov.slminiwifi

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowService
import org.xmlpull.v1.XmlPullParser

/** Launch-only tests: MainActivity is never started and no connection or device request is made. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [SetupLaunchTest.LaunchTileShadow::class])
class SetupLaunchTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun launchIntentReusesTheActivityAndSelectsOnlyTheSetupEntryPoint() {
        val intent = SetupLaunch.intent(context)
        assertSetupIntent(intent)
        assertEquals(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            intent.flags,
        )
    }

    @Test @Config(sdk = [29]) fun android10TileUsesTheLegacyLaunchAndCollapse() {
        val controller = Robolectric.buildService(SetupTileService::class.java).create()
        try {
            val service = controller.get()
            service.onClick()
            val shadow = Shadow.extract<LaunchTileShadow>(service)
            assertSetupIntent(requireNotNull(shadow.legacyIntent))
            assertNull(shadow.pendingIntent)
            assertEquals(1, shadow.launchCount)
        } finally {
            controller.destroy()
        }
    }

    @Test fun android14AndNewerTileUsesAnImmutableActivityPendingIntent() {
        val controller = Robolectric.buildService(SetupTileService::class.java).create()
        try {
            val service = controller.get()
            service.onClick()
            val shadow = Shadow.extract<LaunchTileShadow>(service)
            val pending = shadowOf(requireNotNull(shadow.pendingIntent))
            assertTrue(pending.isActivity)
            assertTrue(pending.isImmutable)
            assertTrue(pending.flags and PendingIntent.FLAG_UPDATE_CURRENT != 0)
            assertSetupIntent(pending.savedIntent)
            assertNull(shadow.legacyIntent)
            assertEquals(1, shadow.launchCount)
        } finally {
            controller.destroy()
        }
    }

    @Test fun tileHasAPlainLabelAndDoesNotClaimWifiIsOn() {
        val controller = Robolectric.buildService(SetupTileService::class.java).create()
        try {
            val service = controller.get()
            service.onStartListening()
            assertEquals(Tile.STATE_INACTIVE, service.qsTile.state)
            assertEquals(context.getString(R.string.setup_launch_label), service.qsTile.label)
            assertEquals(context.getString(R.string.setup_tile_description), service.qsTile.contentDescription)
            assertEquals(0, Shadow.extract<LaunchTileShadow>(service).launchCount)
        } finally {
            controller.destroy()
        }
    }

    @Suppress("DEPRECATION")
    @Test fun onlyTheSystemMayBindTheExportedTile() {
        val service = context.packageManager.getServiceInfo(
            ComponentName(context, SetupTileService::class.java), 0,
        )
        assertTrue(service.exported)
        assertEquals("android.permission.BIND_QUICK_SETTINGS_TILE", service.permission)
        assertEquals(R.drawable.ic_setup_tile, service.icon)
        val matches = context.packageManager.queryIntentServices(Intent(TileService.ACTION_QS_TILE), 0)
        assertEquals(1, matches.count { it.serviceInfo.name == SetupTileService::class.java.name })
    }

    @Suppress("DEPRECATION")
    @Test fun launcherPublishesOneEnabledStaticShortcutToTheSameSetupAction() {
        val activity = context.packageManager.getActivityInfo(
            ComponentName(context, MainActivity::class.java), PackageManager.GET_META_DATA,
        )
        assertNotNull(activity.metaData)
        val resource = activity.metaData.getInt("android.app.shortcuts")
        assertEquals(R.xml.shortcuts, resource)
        val android = "http://schemas.android.com/apk/res/android"
        var shortcuts = 0
        var intents = 0
        context.resources.getXml(resource).use { parser ->
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType != XmlPullParser.START_TAG) continue
                when (parser.name) {
                    "shortcut" -> {
                        shortcuts++
                        assertTrue(parser.getAttributeBooleanValue(android, "enabled", false))
                        assertEquals("set_up_mini", parser.getAttributeValue(android, "shortcutId"))
                        assertEquals(R.string.setup_launch_label,
                            parser.getAttributeResourceValue(android, "shortcutShortLabel", 0))
                    }
                    "intent" -> {
                        intents++
                        assertEquals(SetupLaunch.ACTION, parser.getAttributeValue(android, "action"))
                        assertEquals(context.packageName, parser.getAttributeValue(android, "targetPackage"))
                        assertEquals(SetupShortcutActivity::class.java.name, parser.getAttributeValue(android, "targetClass"))
                    }
                }
            }
        }
        assertEquals(1, shortcuts)
        assertEquals(1, intents)
    }

    @Suppress("DEPRECATION")
    @Test fun shortcutTaskIsSeparateAndNeverRetainedInRecents() {
        val activity = context.packageManager.getActivityInfo(
            ComponentName(context, SetupShortcutActivity::class.java), 0,
        )
        assertTrue(activity.exported)
        assertNull(activity.taskAffinity)
        assertTrue(activity.flags and ActivityInfo.FLAG_NO_HISTORY != 0)
        assertTrue(activity.flags and ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS != 0)
    }

    @Test fun shortcutForwardsOnlyTheFreshSetupIntentAndFinishes() {
        val incoming = Intent(context, SetupShortcutActivity::class.java)
            .setAction(SetupLaunch.ACTION)
            .putExtra("unexpected_extra", "must not be forwarded")
        val controller = Robolectric.buildActivity(SetupShortcutActivity::class.java, incoming).create()
        try {
            val activity = controller.get()
            val outgoing = shadowOf(activity).nextStartedActivity
            assertSetupIntent(requireNotNull(outgoing))
            assertEquals(SetupLaunch.intent(activity).flags, outgoing.flags)
            assertTrue(activity.isFinishing)
            assertNull(shadowOf(activity).nextStartedActivity)
        } finally {
            controller.destroy()
        }
    }

    private fun assertSetupIntent(intent: Intent) {
        assertEquals(SetupLaunch.ACTION, intent.action)
        assertEquals(ComponentName(context, MainActivity::class.java), intent.component)
        assertNull(intent.data)
        assertEquals(setOf(SetupLaunch.EXTRA_LAUNCH_ID), intent.extras!!.keySet())
        assertNotNull(intent.getStringExtra(SetupLaunch.EXTRA_LAUNCH_ID))
    }

    @Implements(TileService::class)
    class LaunchTileShadow : ShadowService() {
        // ShadowTileService does not extend ShadowService, so its default inheritance
        // cannot receive Service.onDestroy during controller cleanup. Preserve the
        // service lifecycle shadow and provide only the tile methods needed here.
        private val tile = Shadow.newInstanceOf(Tile::class.java)
        var legacyIntent: Intent? = null
        var pendingIntent: PendingIntent? = null
        var launchCount = 0

        @Implementation
        protected fun getQsTile(): Tile = tile

        @Implementation
        protected fun startActivityAndCollapse(intent: Intent) {
            legacyIntent = intent
            launchCount++
        }

        @Implementation(minSdk = 34)
        protected fun startActivityAndCollapse(intent: PendingIntent) {
            pendingIntent = intent
            launchCount++
        }
    }
}
