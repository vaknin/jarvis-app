package com.kivan.jarvis

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import com.kivan.jarvis.tabs.TabShareActivity
import com.kivan.jarvis.tabs.TabsActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Every way into the app lands on the wall or in the right tab, and opening it leaves the mic alone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class WaysInTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** The tab a press of this intent asks for, read back as CallActivity reads it. */
    private fun tabOf(i: Intent, answers: Boolean = false) = CallActivity.callTab(answers, i.hasExtra("tab"), i.getStringExtra("tab"))

    private fun launchers() = context.packageManager
        .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName), 0)
        .map { it.activityInfo }

    @Test fun theLauncherLandsOnTheWall() {
        val i = MainActivity.wallIntent(context, install = false)
        assertEquals(TabsActivity::class.java.name, i.component?.className)
        assertFalse(i.hasExtra(Updater.EXTRA_INSTALL))
    }

    @Test fun anUpdateTapLandsOnTheWallStillAsking() {
        assertTrue(MainActivity.wallIntent(context, install = true).getBooleanExtra(Updater.EXTRA_INSTALL, false))
    }

    @Test fun aTabsCallButtonCallsInThatTab() {
        assertEquals("t3", tabOf(CallActivity.intent(context, start = true, tab = "t3", name = "Piano")))
    }

    @Test fun theStemTheTileAndTheAssistantCallInANewTab() {
        // MediaKeys, JarvisTile and Assist all take the default.
        assertEquals(Voice.NEW_TAB, tabOf(CallActivity.intent(context, start = true)))
    }

    @Test fun theShortcutAndAnAssistRequestCarryNoTabAndCallInANewTab() {
        assertEquals(Voice.NEW_TAB, tabOf(Intent(CallActivity.START)))
        assertEquals(Voice.NEW_TAB, tabOf(Intent(Intent.ACTION_ASSIST)))
    }

    @Test fun aTaskCallIsANewTab() {
        assertEquals(Voice.NEW_TAB, tabOf(CallActivity.intent(context, start = true, task = "slug")))
    }

    @Test fun aRingAnsweredIsTheServersToPlace() {
        assertNull(tabOf(CallActivity.intent(context, start = true), answers = true))
    }

    @Test fun theOldCallAlertIsTheServersToPlace() {
        assertNull(tabOf(CallActivity.intent(context, start = true, tab = null)))
    }

    @Test fun aTabNotificationOpensThatTab() {
        val i = TabsActivity.opening(context, "t7")
        assertEquals(TabsActivity::class.java.name, i.component?.className)
        assertEquals("t7", i.getStringExtra("tab"))
    }

    @Test fun pixelIsItsOwnIconAndItsOwnTask() {
        val all = launchers()
        assertEquals(setOf(MainActivity::class.java.name, LaptopActivity::class.java.name), all.map { it.name }.toSet())
        val pixel = all.first { it.name == LaptopActivity::class.java.name }
        assertEquals("com.kivan.jarvis.pixel", pixel.taskAffinity)
        assertEquals("Pixel", pixel.loadLabel(context.packageManager).toString())
        assertTrue(pixel.taskAffinity != all.first { it.name == MainActivity::class.java.name }.taskAffinity)
    }

    @Test fun jarvisStandsBesideLaptopInTheShareSheet() {
        val send = Intent(Intent.ACTION_SEND).setType("image/png").setPackage(context.packageName)
        val found = context.packageManager.queryIntentActivities(send, PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo.name }
        assertTrue(found.toString(), TabShareActivity::class.java.name in found && ShareActivity::class.java.name in found)
        val info = context.packageManager.getActivityInfo(ComponentName(context, TabShareActivity::class.java), 0)
        assertEquals("Jarvis", info.loadLabel(context.packageManager).toString())
    }

    @Test fun theAppOpensSilent() {
        // The screen's whole way up, on the made-up deck, not drawn: the wall's moving line never idles
        // here. The real deck can't load on a host; the emulator's log covers it.
        val tabs = Robolectric.buildActivity(TabsActivity::class.java, Intent().putExtra("tabs", 3)).create().start().resume().get()
        val shadow = shadowOf(tabs)
        assertNull("a service started", shadow.nextStartedService)
        assertNull("something opened", shadow.nextStartedActivity)
        assertNull("it asked for something", shadow.lastRequestedPermission)
        assertFalse(Dictation.on.value)
        assertNull(Dictation.call)
        assertFalse(Voice.ui.value.live)
    }
}
