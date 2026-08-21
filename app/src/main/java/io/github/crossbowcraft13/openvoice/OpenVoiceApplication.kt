package io.github.crossbowcraft13.openvoice

import android.app.Application
import io.github.crossbowcraft13.openvoice.util.Logger
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class OpenVoiceApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Plant the non-blocking logger tree. This must happen at startup —
        // Logger.init used to live in a Hilt provider that nothing injected,
        // so every Logger.d/e call was silently dropped in the real app.
        Logger.init(BuildConfig.DEBUG)
        Logger.i("App started (logger tree planted)", "App")
    }
}
