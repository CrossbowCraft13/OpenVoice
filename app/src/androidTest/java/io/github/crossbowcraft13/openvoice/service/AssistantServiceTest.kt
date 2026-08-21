package io.github.crossbowcraft13.openvoice.service

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import io.github.crossbowcraft13.openvoice.ui.MainActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssistantServiceTest {

    // The service declares foregroundServiceType="microphone" (targetSdk 35), so
    // startForeground() throws SecurityException unless RECORD_AUDIO is granted.
    // Grant it up front instead of relying on the app's persisted permission
    // state, which differs between fresh CI emulators and reused local ones.
    @get:Rule
    val recordAudioPermission: GrantPermissionRule =
        GrantPermissionRule.grant(android.Manifest.permission.RECORD_AUDIO)

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun serviceStartsAndRegistersInstance() {
        // Bring the app to the foreground first: Android 12+ forbids
        // startForegroundService from a background app.
        val scenario = ActivityScenario.launch(MainActivity::class.java)

        try {
            // API 35 gives a startForegroundService -> startForeground window of
            // ~5s; on a freshly-started or busy process the ANR fires before the
            // service ever runs, crashing the process with
            // ForegroundServiceDidNotStartInTimeException. Wait for the main
            // thread to go idle and give the cold process a moment to settle so
            // the service create is dispatched promptly.
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                .waitForIdleSync()
            Thread.sleep(1000)

            AssistantService.stop(context)
            // stopService() and startForegroundService() are async binder
            // calls. If the stop lands after the start, the system kills the
            // process with ForegroundServiceDidNotStartInTimeException — the
            // same settle ReliabilityManager.restartService() uses between
            // its stop and start.
            Thread.sleep(500)
            assertFalse(AssistantService.isRunning())

            AssistantService.start(context)
            // startForegroundService is asynchronous; poll for registration
            // for the full 5s window so slow (but healthy) emulators pass.
            var running = false
            repeat(50) {
                if (AssistantService.isRunning()) {
                    running = true
                    return@repeat
                }
                Thread.sleep(100)
            }
            assertTrue("service should register itself as running", running)

            AssistantService.stop(context)
            Thread.sleep(200)
            assertFalse(AssistantService.isRunning())
        } finally {
            scenario.close()
        }
    }
}
