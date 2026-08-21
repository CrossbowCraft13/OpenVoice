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

# llama.cpp is pinned as a Git submodule (vendor/llama.cpp). Without this
# step the native build fails at CMake configure time.
git submodule update --init --recursive
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

The project includes two native JNI libraries, compiled via CMake + NDK
(`app/CMakeLists.txt`). Release ships only `arm64-v8a` and `armeabi-v7a`;
debug builds additionally include `x86_64` so the local/CI emulator can load
the REAL llama.cpp library and run real inference (see the smoke test below).
(see `abiFilters` in `app/build.gradle.kts`):

| Library | Source | Integration |
|---------|--------|-------------|
| `libwhisper_bridge.so` | `app/src/main/cpp/whisper_jni.cpp` | Whisper.cpp (speech-to-text) |
| `libllama_bridge.so` | `app/src/main/cpp/llama_jni.cpp` | **Upstream llama.cpp** (LLM inference) |

### llama.cpp integration

`libllama_bridge.so` is a real integration, not a stub:

- **Upstream source**: [llama.cpp](https://github.com/ggml-org/llama.cpp) is pinned
  as a Git submodule at `vendor/llama.cpp` (currently tag `b10326`).
- **Build**: `app/CMakeLists.txt` adds the submodule with `add_subdirectory()`,
  builds `llama` + `ggml`/`ggml-base`/`ggml-cpu` as **static** libraries
  (`BUILD_SHARED_LIBS=OFF`) and links them into `libllama_bridge.so`, so the
  app ships a single JNI library per ABI.
- **Flags**: examples/tests/tools/common are off (`LLAMA_STANDALONE=OFF` when
  added as a subdirectory); `GGML_OPENMP=OFF` and `GGML_NATIVE=OFF` keep the
  build NDK-friendly and portable.
- **JNI**: `llama_jni.cpp` calls the upstream `llama.h` C API directly
  (sampler chains, `llama_decode`, embeddings, metadata, abort-callback
  cancellation). `LlamaCppBridge.kt` is unchanged.

### Updating llama.cpp

```bash
cd vendor/llama.cpp
git fetch origin tag bXXXXX
git checkout bXXXXX
cd ../..
git add vendor/llama.cpp
# Then verify the JNI still matches the new llama.h API:
./gradlew :app:externalNativeBuildDebug
```

> The JNI is written against the API of the pinned tag. After a bump, check
> `app/src/main/cpp/llama_jni.cpp` against `vendor/llama.cpp/include/llama.h`
> for renamed/removed functions (e.g. `llama_kv_cache_clear` was replaced by
> the `llama_memory_*` API).

### Running real inference on the emulator (x86_64)

Debug builds include the `x86_64` ABI, so the emulator CAN load `libllama_bridge.so`
and execute real llama.cpp inference. `NativeSmokeTest` drives the full native
round trip — load GGUF, metadata, token counts, sync + streaming generation,
reset/cancel, release — but needs a tiny model staged into the app's **internal**
`files/smoke` dir (shell-pushed files in the external `Android/data` dir are
hidden from apps by FUSE on Android 11+):

```bash
# download a tiny test model (TinyStories — llama.cpp's own CI model)
curl -L -o stories15M.gguf \
  https://huggingface.co/ggml-org/models-moved/resolve/main/tinyllamas/stories15M.gguf

# build, install, stage the model, run the smoke test
./gradlew assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb push stories15M.gguf /data/local/tmp/
adb shell "run-as io.github.crossbowcraft13.openvoice.debug sh -c \
  'mkdir -p files/smoke && cat /data/local/tmp/stories15M.gguf > files/smoke/stories15M.gguf'"
adb shell am instrument -w \
  -e class io.github.crossbowcraft13.openvoice.NativeSmokeTest \
  -e modelPath /data/user/0/io.github.crossbowcraft13.openvoice.debug/files/smoke/stories15M.gguf \
  io.github.crossbowcraft13.openvoice.debug.test/androidx.test.runner.AndroidJUnitRunner
```

The smoke dir is deliberately separate from `files/models` (where ModelManager
and its tests enumerate installed models) so the two never collide. Without a
staged model the test skips (CI stays green); with one it proves the whole
native stack executes for real. On arm devices no staging is needed — the app's
ModelManager downloads GGUF files into internal storage directly.

## Common Issues

### NDK Not Found
```
Install NDK 25.2.9519653 via Android Studio SDK Manager.
Set ANDROID_NDK_HOME environment variable.
```

### CMake configure fails with a llama.cpp error
```
You cloned without submodules. Run:
  git submodule update --init --recursive
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
- `release.yml` — Auto-release: every merge to `main` bumps the version in the
  root `VERSION` file, builds and signs the release APK + AAB, tags the repo
  `v<version>`, and publishes a GitHub Release with changelog and checksums.
  Pushing a `v*` tag manually (or using the `workflow_dispatch` trigger)
  publishes that version without bumping.

`VERSION` (repo root, format `X.Y.Z` or `X.Y.Z-suffix` like `1.0.1-beta`) is the
single source of truth for `versionName`/`versionCode` — `app/build.gradle.kts`
reads it at configuration time. The release workflow needs four repository
secrets for signing: `SIGNING_KEY` (base64-encoded keystore), `KEY_ALIAS`,
`KEY_STORE_PASSWORD`, and `KEY_PASSWORD`.
