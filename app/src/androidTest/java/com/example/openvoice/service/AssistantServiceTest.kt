package com.example.openvoice.service

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.openvoice.ui.MainActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssistantServiceTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun serviceStartsAndRegistersInstance() {
        // Bring the app to the foreground first: Android 12+ forbids
        // startForegroundService from a background app.
        val scenario = ActivityScenario.launch(MainActivity::class.java)

        try {
            AssistantService.stop(context)
            assertFalse(AssistantService.isRunning())

            AssistantService.start(context)
            // startForegroundService is asynchronous; poll for registration.
            var running = false
            repeat(20) {
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
