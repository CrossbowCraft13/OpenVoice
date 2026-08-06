# Development scripts

## Environment check

Run the read-only prerequisite check before the first build or when Gradle sync reports a toolchain error:

```bash
./scripts/check-environment.sh
```

It checks Java 17, the included Gradle Wrapper 8.6, Android SDK 35, Android NDK 25.2.9519653, CMake, and `adb`. It does not install packages, change environment variables, modify `local.properties`, or alter project files.

For the supported setup, install Android Studio Hedgehog or newer and use SDK Manager to install:

- Android SDK Platform 35
- Android SDK Build-Tools
- Android NDK 25.2.9519653
- CMake 3.22.1

The project uses Java 17. Android Studio includes a compatible JDK; command-line builds require `JAVA_HOME` to point to JDK 17. The included wrapper downloads Gradle 8.6 automatically. Set `ANDROID_SDK_ROOT` (preferred) or `ANDROID_HOME` to the SDK directory.
