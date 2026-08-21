# ============================================================
# OpenVoice ProGuard / R8 Rules
# Privacy-first: never obfuscate user data paths
# ============================================================

# Keep ONNX Runtime
-keep class ai.onnxruntime.** { *; }

# Keep JNI bridge for Whisper.cpp
-keep class io.github.crossbowcraft13.openvoice.stt.WhisperCppBridge { *; }
-keep class io.github.crossbowcraft13.openvoice.stt.WhisperCppBridge$* { *; }
-keepclassmembers class io.github.crossbowcraft13.openvoice.stt.WhisperCppBridge {
    native <methods>;
}

# Hilt/Dagger
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }

# Room database
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao class *

# Keep data models for serialization
-keep class io.github.crossbowcraft13.openvoice.data.** { *; }

# Keep UI models
-keep class io.github.crossbowcraft13.openvoice.ui.** { *; }

# Keep accessibility service
-keep class io.github.crossbowcraft13.openvoice.accessibility.** { *; }

# Keep operator classes
-keep class io.github.crossbowcraft13.openvoice.operator.** { *; }

# Keep VAD and wake word engines
-keep class io.github.crossbowcraft13.openvoice.vad.** { *; }
-keep class io.github.crossbowcraft13.openvoice.wakeword.** { *; }

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Timber logging
-keep class timber.log.Timber { *; }

# General rules
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes Exceptions
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**

# Remove logging in release builds
-assumenosideeffects class timber.log.Timber {
    public static *** v(...);
    public static *** d(...);
}
