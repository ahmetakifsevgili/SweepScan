package com.sweepscan.ui

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.sweepscan.R
import com.sweepscan.SweepScanApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Presses every control on both screens of the debug build (com.sweepscan.debug, its own data) on a
 * phone or emulator, with a small count in the session. Any crash fails the run. Run before every
 * install: `tools\decode_bench.bat` runs all on-device tests.
 *
 * Plain instrumentation, no Espresso: Espresso 3.6 fails on Android 17 (InputManager.getInstance).
 */
@RunWith(AndroidJUnit4::class)
class ScreensSmokeTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.CAMERA, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE,
    )

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = ApplicationProvider.getApplicationContext<SweepScanApp>()

    /** The phone may be asleep and locked during the run: show the test's activities over the lock screen. */
    private val overLock = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) {
            activity.setShowWhenLocked(true)
            activity.setTurnScreenOn(true)
        }
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    @After
    fun tearDown() = app.unregisterActivityLifecycleCallbacks(overLock)

    @Before
    fun seed() {
        app.registerActivityLifecycleCallbacks(overLock)
        instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
        app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("guide_shown", true).commit()
        instrumentation.runOnMainSync {
            app.session.clear()
            listOf("5895214670690", "5895214670690", "4308704539281", "8459744815876").forEach { app.session.add(it) }
        }
    }

    private fun resumed(): Activity {
        repeat(50) {
            var found: Activity? = null
            instrumentation.runOnMainSync {
                found = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull()
            }
            found?.let { return it }
            Thread.sleep(100)
        }
        error("no resumed activity")
    }

    private fun settle(ms: Long = 400) {
        instrumentation.waitForIdleSync()
        Thread.sleep(ms)
    }

    /** Clicks a visible, enabled control (toggle-group buttons have no click listener, so no result check). */
    private fun click(id: Int) {
        val a = resumed()
        var ok = false
        instrumentation.runOnMainSync {
            val v = a.findViewById<View>(id)
            ok = v != null && v.isShown && v.isEnabled
            if (ok) v.performClick()
        }
        assertTrue("click ${a.resources.getResourceEntryName(id)} on ${a.javaClass.simpleName}", ok)
        settle()
    }

    /** Back only while a dialog or sheet has the focus, so it never leaves the screen by accident. */
    private fun dismiss() {
        val a = resumed()
        var covered = false
        instrumentation.runOnMainSync { covered = !a.hasWindowFocus() }
        if (covered) key(KeyEvent.KEYCODE_BACK)
    }

    private fun visible(id: Int): Boolean {
        val a = resumed()
        var v = false
        instrumentation.runOnMainSync { v = a.findViewById<View>(id)?.isShown == true }
        return v
    }

    private fun key(code: Int) {
        instrumentation.sendKeyDownUpSync(code)
        settle()
    }

    /** A Compose node by its test tag (the review screen exposes tags as resource ids), in any window. */
    private fun node(tag: String? = null, text: String? = null): AccessibilityNodeInfo? {
        val ua = instrumentation.uiAutomation
        val roots = listOfNotNull(ua.rootInActiveWindow) + runCatching { ua.windows.mapNotNull { it.root } }.getOrDefault(emptyList())
        fun find(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (n == null) return null
            if (tag != null && n.viewIdResourceName == tag) return n
            if (text != null && n.text?.toString() == text) return n
            for (i in 0 until n.childCount) find(n.getChild(i))?.let { return it }
            return null
        }
        for (r in roots) find(r)?.let { return it }
        return null
    }

    private fun waitNode(tag: String? = null, text: String? = null): AccessibilityNodeInfo? {
        repeat(40) {
            node(tag, text)?.let { return it }
            Thread.sleep(100)
        }
        return null
    }

    private fun shown(tag: String) = waitNode(tag) != null

    /** Clicks the node with [tag] or [text] (or the nearest clickable node around it). */
    private fun tap(tag: String? = null, text: String? = null) {
        val n = waitNode(tag, text)
        assertTrue("found ${tag ?: text}", n != null)
        fun clickable(x: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (x.isClickable) return x
            for (i in 0 until x.childCount) x.getChild(i)?.let { c -> clickable(c)?.let { return it } }
            return null
        }
        var target: AccessibilityNodeInfo? = clickable(n!!)
        var up: AccessibilityNodeInfo? = n
        while (target == null && up != null) { if (up.isClickable) target = up; up = up.parent }
        assertTrue("click ${tag ?: text}", target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        settle()
    }

    @Test
    fun scanScreenEveryControlThenReview() {
        ActivityScenario.launch(MainActivity::class.java).use {
            settle(2500) // camera up
            assertTrue("recent strip", visible(R.id.recent_strip))

            // Pause: the picture dims, controls stay usable (settings reachable while paused).
            click(R.id.btn_scan)
            assertTrue("paused", visible(R.id.paused_overlay))
            click(R.id.btn_settings)
            settle(600)
            dismiss() // closes the settings sheet
            click(R.id.btn_scan)
            assertFalse("resumed", visible(R.id.paused_overlay))

            // Volume key = pause / resume. A lock screen that is awake takes the volume keys itself.
            val keyguard = app.getSystemService(android.app.KeyguardManager::class.java)
            val awake = app.getSystemService(android.os.PowerManager::class.java).isInteractive
            if (!(awake && keyguard.isKeyguardLocked)) {
                key(KeyEvent.KEYCODE_VOLUME_DOWN)
                assertTrue("paused by volume", visible(R.id.paused_overlay))
                key(KeyEvent.KEYCODE_VOLUME_DOWN)
                assertFalse("resumed by volume", visible(R.id.paused_overlay))
            }

            // Zoom buttons, AUTO, torch, undo.
            click(R.id.zoom_2)
            click(R.id.zoom_auto)
            click(R.id.btn_torch)
            click(R.id.btn_torch)
            click(R.id.btn_undo)

            // Finish -> review screen, change a line, back to scanning.
            click(R.id.btn_finish)
            settle(1200)
            assertEquals(SessionActivity::class.java, resumed().javaClass)
            assertTrue("summary", shown("summary"))
            tap("plus_0")
            tap("minus_0")
            key(KeyEvent.KEYCODE_BACK)
            settle(1500)
            assertEquals(MainActivity::class.java, resumed().javaClass)
            assertTrue("scan button", visible(R.id.btn_scan))
        }
    }

    @Test
    fun reviewScreenSendAndMenu() {
        ActivityScenario.launch(SessionActivity::class.java).use {
            settle(800)
            assertTrue("summary", shown("summary"))
            assertTrue("summary status", shown("summary_status"))
            assertTrue("pc line", shown("progress"))

            // Send without a connected PC: a message (and the PC menu when Bluetooth is on), no crash.
            tap("btn_send")
            settle(600)
            dismiss()

            // PC line opens the PC menu.
            tap("progress")
            dismiss()

            // Menu: manual add, then new session (both open a dialog; Back cancels).
            tap("menu")
            tap(text = app.getString(R.string.add_manual))
            dismiss()
            tap("menu")
            tap(text = app.getString(R.string.new_session))
            dismiss()
            settle(600)
            assertTrue("count kept", app.session.total() > 0)
            assertEquals(SessionActivity::class.java, resumed().javaClass)
        }
    }
}
