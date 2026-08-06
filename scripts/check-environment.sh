#!/bin/sh
set -u

required_gradle="8.6"
required_compile_sdk="35"
required_ndk_version="25.2.9519653"
missing=0

say() {
    printf '%s\n' "$*"
}

check_command() {
    label=$1
    command_name=$2
    if command -v "$command_name" >/dev/null 2>&1; then
        say "PASS  $label: $(command -v "$command_name")"
    else
        say "MISS  $label: '$command_name' was not found"
        missing=1
    fi
}

say "OpenVoice development environment"
say ""

check_command "Java 17 runtime" java
if [ -x "./gradlew" ]; then
    say "PASS  Gradle Wrapper: ./gradlew"
else
    say "MISS  Gradle Wrapper: './gradlew' was not found"
    missing=1
fi

if command -v java >/dev/null 2>&1; then
    java_version=$(java -version 2>&1 | awk -F '"' '/version/ { print $2; exit }')
    case "$java_version" in
        17*) say "PASS  Java version: $java_version" ;;
        *) say "WARN  Java version: ${java_version:-unknown} (this project requires Java 17)"; missing=1 ;;
    esac
fi

if [ -x "./gradlew" ] && command -v java >/dev/null 2>&1; then
    gradle_version=$(./gradlew --version 2>/dev/null | awk '/^Gradle [0-9]/ { print $2; exit }')
    if [ "$gradle_version" = "$required_gradle" ]; then
        say "PASS  Gradle Wrapper version: $gradle_version"
    else
        say "WARN  Gradle Wrapper version: ${gradle_version:-unknown} (this project targets $required_gradle)"
        missing=1
    fi
fi

sdk_dir=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
if [ -z "$sdk_dir" ] && [ -f local.properties ]; then
    sdk_dir=$(awk -F= '/^sdk\.dir=/ { print substr($0, index($0, "=") + 1); exit }' local.properties)
    sdk_dir=$(printf '%s' "$sdk_dir" | sed 's#\\:#:#g')
fi

if [ -n "$sdk_dir" ] && [ -d "$sdk_dir" ]; then
    say "PASS  Android SDK: $sdk_dir"
    if command -v adb >/dev/null 2>&1 || [ -x "$sdk_dir/platform-tools/adb" ]; then
        say "PASS  Android Debug Bridge"
    else
        say "MISS  Android Debug Bridge: install Android SDK Platform-Tools"
        missing=1
    fi
    if command -v cmake >/dev/null 2>&1 || [ -x "$sdk_dir/cmake/3.22.1/bin/cmake" ]; then
        say "PASS  CMake"
    else
        say "MISS  CMake: install CMake 3.22.1 through SDK Manager"
        missing=1
    fi
    if [ -d "$sdk_dir/platforms/android-$required_compile_sdk" ]; then
        say "PASS  Android platform: android-$required_compile_sdk"
    else
        say "MISS  Android platform: android-$required_compile_sdk is not installed"
        missing=1
    fi
    if [ -d "$sdk_dir/ndk" ]; then
        ndk_found=0
        for ndk_path in "$sdk_dir"/ndk/*; do
            [ -d "$ndk_path" ] || continue
            ndk_version=${ndk_path##*/}
            if [ "$ndk_version" = "$required_ndk_version" ]; then
                say "PASS  Android NDK: $ndk_version"
                ndk_found=1
                break
            fi
        done
        if [ "$ndk_found" -eq 0 ]; then
            say "MISS  Android NDK: version $required_ndk_version is required"
            missing=1
        fi
    else
        say "MISS  Android NDK: SDK NDK directory was not found"
        missing=1
    fi
else
    say "MISS  Android SDK: set ANDROID_SDK_ROOT/ANDROID_HOME or create local.properties"
    say "MISS  Android Debug Bridge: SDK location is unavailable"
    say "MISS  CMake: SDK location is unavailable"
    missing=1
fi

say ""
if [ "$missing" -eq 0 ]; then
    say "Environment check passed. Run: ./gradlew assembleDebug"
    exit 0
fi

say "Environment check failed. Recommended setup:"
say "  1. Install Android Studio Hedgehog or newer."
say "  2. In SDK Manager install Android SDK $required_compile_sdk, Android SDK Build-Tools, NDK $required_ndk_version, and CMake 3.22.1."
say "  3. Use Android Studio's bundled JDK 17, or install a standalone JDK 17 and set JAVA_HOME."
say "  4. Use the included Gradle Wrapper; it downloads Gradle $required_gradle automatically."
say "  5. Set ANDROID_SDK_ROOT (or ANDROID_HOME), then rerun: ./scripts/check-environment.sh"
say "  6. Build with: ./gradlew assembleDebug"
exit 1
