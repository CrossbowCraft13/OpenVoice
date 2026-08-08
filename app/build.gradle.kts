import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "com.example.openvoice"
    compileSdk = 35
    ndkVersion = "25.2.9519653"

    defaultConfig {
        applicationId = "com.example.openvoice"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0-beta"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables {
            useSupportLibrary = true
        }

        // NDK configuration for whisper.cpp JNI
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_shared"
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            enableUnitTestCoverage = true
            enableAndroidTestCoverage = true
            // Debug builds also target x86_64 so the local/CI emulator can load
            // the REAL llama.cpp library and run real inference (see
            // NativeSmokeTest). Release keeps shipping arm ABIs only.
            externalNativeBuild {
                cmake {
                    abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
                }
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.10"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE"
            excludes += "/META-INF/LICENSE.txt"
        }
    }

    // NDK/CMake configuration for native code
    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Configure source sets
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    // Note: AGP 8.x removed the unitTests jacoco { includeNoLocationClasses }
    // DSL — AGP 8 handles no-location Kotlin classes internally, so the
    // coverage flags above are all that is needed.
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")

    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2024.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material:material-icons-core")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Hilt - Dependency Injection
    implementation("com.google.dagger:hilt-android:2.50")
    kapt("com.google.dagger:hilt-android-compiler:2.50")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")
    implementation("androidx.hilt:hilt-work:1.2.0")
    kapt("androidx.hilt:hilt-compiler:1.2.0")

    // Room - Local Database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    // DataStore - Preferences
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // Security - Encryption
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.security:security-app-authenticator:1.0.0-beta01")
    implementation("androidx.biometric:biometric:1.2.0-alpha05")

    // WorkManager - Background Tasks
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.work:work-rxjava2:2.9.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    // TensorFlow Lite - Wake Word & NLU models
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")

    // ONNX Runtime - Piper TTS & Silero VAD
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.16.3")

    // Audio processing utilities

    // Logging
    implementation("com.jakewharton.timber:timber:5.0.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("androidx.arch.core:core-testing:2.2.0")
    testImplementation("com.google.truth:truth:1.1.3")

    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation("androidx.test.espresso:espresso-intents:3.5.1")
    androidTestImplementation("androidx.test.espresso:espresso-contrib:3.5.1")
    androidTestImplementation("androidx.test:runner:1.5.0")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("com.google.truth:truth:1.1.3")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")

    // Compose UI testing
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.01.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Hilt annotation processing
kapt {
    correctErrorTypes = true
}

// ---------------------------------------------------------------------------
// JaCoCo coverage report tasks
// ---------------------------------------------------------------------------
// Coverage artifacts:
//   - unit tests:  build/outputs/unit_test_code_coverage/debugUnitTest/*.exec
//   - instrumented: build/outputs/code_coverage/debugAndroidTest/connected/**/*.ec
// The merged report (jacocoFullReport) combines both so the overall number
// reflects unit + on-device coverage. Generated code (Hilt/Dagger/Room,
// R, BuildConfig) is excluded from the metric.

// NOTE: these report tasks deliberately do NOT apply the org.gradle.jacoco plugin.
// AGP creates the jacocoAgent/jacocoAnt configurations itself whenever the
// enableUnitTestCoverage / enableAndroidTestCoverage flags above are on, so the
// JacocoReport task types resolve from the bundled Gradle distribution. Do not
// remove those flags without also re-testing jacocoFullReport.

val jacocoExcludedClasses = listOf(
    "**/R.class",
    "**/R\$*.class",
    "**/BuildConfig.*",
    "**/Hilt_*.class",
    "**/*_HiltModules*.class",
    "**/*_Factory.class",
    "**/*_MembersInjector.class",
    "**/*_GeneratedInjector.class",
    "**/*_HiltComponents*.class",
    "**/Dagger*.class",
    "**/*_Impl*.class",
)

val jacocoClassDirs = fileTree(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) {
        exclude(jacocoExcludedClasses)
    } + fileTree(layout.buildDirectory.dir("intermediates/javac/debug/classes")) {
        exclude(jacocoExcludedClasses)
    }

val jacocoSourceDirs = files("src/main/java")

// Execution data produced by the coverage-instrumented test runs.
val jacocoUnitExec = fileTree(layout.buildDirectory.dir("outputs/unit_test_code_coverage/debugUnitTest")) {
    include("*.exec")
}
val jacocoAndroidTestEc = fileTree(layout.buildDirectory.dir("outputs/code_coverage/debugAndroidTest/connected")) {
    include("**/*.ec")
}

fun org.gradle.testing.jacoco.tasks.JacocoReport.configureJacocoReport(desc: String) {
    group = "verification"
    description = desc
    classDirectories.from(jacocoClassDirs)
    sourceDirectories.from(jacocoSourceDirs)
    reports {
        html.required.set(true)
        xml.required.set(true)
        csv.required.set(false)
    }
}

tasks.register<JacocoReport>("jacocoUnitTestReport") {
    configureJacocoReport("JaCoCo coverage report from local unit tests only.")
    dependsOn("testDebugUnitTest")
    executionData.from(jacocoUnitExec)
}

tasks.register<JacocoReport>("jacocoAndroidTestReport") {
    configureJacocoReport("JaCoCo coverage report from instrumented tests on device/emulator only.")
    dependsOn("connectedDebugAndroidTest")
    executionData.from(jacocoAndroidTestEc)
    onlyIf("no .ec coverage files were produced by connectedDebugAndroidTest") {
        jacocoAndroidTestEc.files.isNotEmpty()
    }
}

tasks.register<JacocoReport>("jacocoFullReport") {
    configureJacocoReport("Merged JaCoCo coverage report (unit + instrumented tests).")
    dependsOn("testDebugUnitTest")
    dependsOn("connectedDebugAndroidTest")
    executionData.from(jacocoUnitExec, jacocoAndroidTestEc)
    onlyIf("both unit (.exec) and instrumented (.ec) execution data must exist " +
        "so the merged report is never a silent unit-only number") {
        jacocoUnitExec.files.isNotEmpty() && jacocoAndroidTestEc.files.isNotEmpty()
    }
}
