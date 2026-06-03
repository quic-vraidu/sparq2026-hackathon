plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.aster.ondevice"
    compileSdk = 35

    // libQnnHtpV81Stub.so is a Qualcomm BSP lib pre-installed at /vendor/lib64/ on all
    // Snapdragon devices. Bundling it in the APK causes it to load in the app's linker
    // namespace (clns-*) which lacks vendor lib access, breaking its HIDL dependencies.
    // Exclude it here and declare it as uses-native-library in the manifest instead.
    //
    // extractNativeLibs=true: required for libQnnHtpV81Skel.so (the CDSP/DSP binary).
    // The ADSP RPC needs a real filesystem path to the Skel — it cannot load from a
    // compressed mmap inside the APK (hence the "Failed to punch uncompressed elf" warning).
    // Extraction writes the .so to nativeLibraryDir, which is on ADSP_LIBRARY_PATH.
    packagingOptions {
        // libcdsprpc.so is a Qualcomm BSP lib at /vendor/lib64/; declared as
        // uses-native-library in manifest (required=true) so it loads from the vendor
        // namespace, giving libQnnHtpV81Stub.so access to HIDL/vendor deps.
        // libQnnHtpV81Skel.so is the DSP-side binary; the system copy in
        // /vendor/dsp/cdsp/ is version-matched to the DSP firmware — use that.
        jniLibs.excludes += setOf(
            "lib/arm64-v8a/libcdsprpc.so",
            "lib/arm64-v8a/libQnnHtpV81Skel.so",
        )
        jniLibs.useLegacyPackaging = true   // extract .so to disk (required for ADSP Skel path)
    }

    defaultConfig {
        applicationId = "com.aster.ondevice"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        ndk { abiFilters += "arm64-v8a" }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-O3", "-DNDEBUG")
                // Phase 2 Genie: no extra args needed; aster_genie is built
                // conditionally by CMakeLists.txt when Genie SDK headers/libs are present.
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
            // lib-debug.aar was compiled with Kotlin 2.3.x metadata; skip the version check
            // so Kotlin 2.0.21 doesn't reject it.
            freeCompilerArgs.add("-Xskip-metadata-version-check")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // ARM AI Chat AAR — Phase 1 llama.cpp backend (prebuilt, arm64-v8a)
    implementation(files("libs/lib-debug.aar"))

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.ui.tooling)

    // Core Android
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // LiteRT-LM — Google MediaPipe LLM Inference API (Phase 3 / Gemma 4 4B on-device)
    // Check latest version at: https://mvnrepository.com/artifact/com.google.mediapipe/tasks-genai
    implementation("com.google.mediapipe:tasks-genai:0.10.22")

    // CameraX    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.video)
    implementation(libs.androidx.camera.view)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Serialization
    implementation(libs.kotlinx.serialization.json)

    // OkHttp — cloud inference (QAIC gateway)
    implementation(libs.okhttp)

    // Location
    implementation(libs.play.services.location)

    // Media (ExoPlayer for audio)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
}
