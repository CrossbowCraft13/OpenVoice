# ============================================================
# OpenVoice ProGuard / R8 Rules
# Privacy-first: never obfuscate user data paths
# ============================================================

# Keep TensorFlow Lite models
-keep class org.tensorflow.lite.** { *; }
-keep class org.tensorflow.lite.support.** { *; }

# Keep ONNX Runtime
-keep class ai.onnxruntime.** { *; }

# Keep JNI bridge for Whisper.cpp
-keep class com.example.openvoice.stt.WhisperCppBridge { *; }
-keep class com.example.openvoice.stt.WhisperCppBridge$* { *; }
-keepclassmembers class com.example.openvoice.stt.WhisperCppBridge {
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
-keep class com.example.openvoice.data.** { *; }

# Keep UI models
-keep class com.example.openvoice.ui.** { *; }

# Keep accessibility service
-keep class com.example.openvoice.accessibility.** { *; }

# Keep operator classes
-keep class com.example.openvoice.operator.** { *; }

# Keep VAD and wake word engines
-keep class com.example.openvoice.vad.** { *; }
-keep class com.example.openvoice.wakeword.** { *; }

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
