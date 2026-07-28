# Build Guide

## Prerequisites

- **Android Studio**: Hedgehog (2023.1.1+) or later
- **Android SDK**: Version 35
- **NDK**: Version 25+ (for native whisper.cpp and llama.cpp)
- **JDK**: Version 17
- **Gradle**: 8.4 (wrapper included)

## Step-by-Step Build

### 1. Clone the Repository

```bash
git clone https://github.com/yourusername/openvoice.git
cd openvoice
```

### 2. Set Up Android SDK

Ensure your `local.properties` points to the Android SDK:

```bash
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
```

Or open the project in Android Studio — it will create `local.properties` automatically.

### 3. Build the App

```bash
# Debug build (fast, no optimization)
./gradlew assembleDebug

# Release build (optimized, R8 minification)
./gradlew assembleRelease

# Bundle for Play Store
./gradlew bundleRelease
```

### 4. Run Tests

```bash
# Unit tests (fast, run on JVM)
./gradlew testDebugUnitTest

# Instrumentation tests (run on device/emulator)
./gradlew connectedDebugAndroidTest

# All checks
./gradlew lint detekt ktlintCheck
```

### 5. Install on Device

```bash
# Install debug APK
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Build Variants

| Variant | Minification | Debuggable | Use Case |
|---------|-------------|------------|----------|
| `debug` | Off | Yes | Development |
| `release` | R8 | No | Production |

## Native Code

The project includes two native JNI libraries:

| Library | Source | Integration |
|---------|--------|-------------|
| `libwhisper_bridge.so` | `app/src/main/cpp/whisper_jni.cpp` | Whisper.cpp (speech-to-text) |
| `libllama_bridge.so` | `app/src/main/cpp/llama_jni.cpp` | Llama.cpp (LLM inference) |

These are compiled via CMake + NDK. The build is configured in `app/CMakeLists.txt`.

### Adding Whisper.cpp

```bash
# Add as Git submodule
git submodule add https://github.com/ggerganov/whisper.cpp.git app/whisper.cpp

# Update CMakeLists.txt to link libwhisper
# See comments in app/CMakeLists.txt
```

## Common Issues

### NDK Not Found
```
Install NDK 25+ via Android Studio SDK Manager.
Set ANDROID_NDK_HOME environment variable.
```

### Missing local.properties
```
Create local.properties with:
  sdk.dir=/path/to/Android/Sdk
  ndk.dir=/path/to/Android/Sdk/ndk/25.x.x
```

### Gradle Sync Fails
```
./gradlew clean
./gradlew --refresh-dependencies
```

## CI/CD

The project uses GitHub Actions. See `.github/workflows/` for:
- `ci.yml` — Build, test, lint on every push
- `release.yml` — Signed release on version tags
