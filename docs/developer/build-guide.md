# Build Guide

## Prerequisites

- **Android Studio**: Hedgehog (2023.1.1+) or later
- **Android SDK**: Version 35
- **NDK**: Version 25.2.9519653 (for native whisper.cpp and llama.cpp)
- **JDK**: Version 17
- **Gradle**: 8.6 via the included Gradle Wrapper (no separate Gradle install required)

## Step-by-Step Build

### 1. Clone the Repository

```bash
git clone https://github.com/CrossbowCraft13/openvoice.git
cd openvoice
```

### 2. Check the development environment

Run the repository's read-only prerequisite check before building:

```bash
./scripts/check-environment.sh
```

It checks Java 17, Gradle 8.6, Android SDK 35, NDK 25.2.9519653, CMake, and `adb`. It does not install software or modify project files.

### 3. Set Up Android SDK

Ensure your `local.properties` points to the Android SDK:

```bash
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
```

Or open the project in Android Studio — it will create `local.properties` automatically.

### 4. Build the App

```bash
# Debug build (fast, no optimization)
./gradlew assembleDebug

# Release build (optimized, R8 minification)
./gradlew assembleRelease

# Bundle for Play Store
./gradlew bundleRelease
```

### 5. Run Tests

```bash
# Unit tests (fast, run on JVM)
./gradlew testDebugUnitTest

# Instrumentation tests (run on device/emulator)
./gradlew connectedDebugAndroidTest

# All checks
./gradlew lint
```

### 6. Install on Device

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
Install NDK 25.2.9519653 via Android Studio SDK Manager.
Set ANDROID_NDK_HOME environment variable.
```

### Missing local.properties
```
Create local.properties with:
  sdk.dir=/path/to/Android/Sdk
  ndk.dir=/path/to/Android/Sdk/ndk/25.x.x
```

> **Windows users**: `local.properties` is a Java properties file, so backslashes are escape
> characters. `sdk.dir=C:\Users\me\Android\Sdk` silently becomes `C:UsersmeAndroidSdk` and the
> build fails with a confusing path error. Use forward slashes instead:
> `sdk.dir=C:/Users/me/Android/Sdk` (or escape every backslash).

### Gradle Sync Fails
```
./gradlew clean
./gradlew --refresh-dependencies
```

## CI/CD

The project uses GitHub Actions. See `.github/workflows/` for:
- `ci.yml` — Build, test, lint on every push
- `release.yml` — Signed release on version tags
