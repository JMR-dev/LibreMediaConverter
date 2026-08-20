plugins {
    alias(libs.plugins.android.application)
    // Required even under AGP 9: the Compose compiler plugin is NOT built in.
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.jasonmross.mediaconverter"

    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "dev.jasonmross.mediaconverter"
        minSdk = 33
        // AGP 9 defaults targetSdk to compileSdk, so always state it explicitly.
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 64-bit only. The 16 KB page-size rules apply to these ABIs; 32-bit is not
        // required by Play and would add ~2 more copies of the FFmpeg .so files.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        release {
            optimization {
                // R8 full mode. Keep rules for the JNI boundary live in
                // src/main/keepRules/rules.keep -- without them the native FFmpeg
                // calls break at runtime in release builds only.
                enable = true
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            // Uncompressed .so, so the APK zip-aligns them on 16 KB boundaries.
            useLegacyPackaging = false
        }
    }
}

// Top-level: android.kotlinOptions {} was removed in AGP 9.
// jvmTarget is inherited from compileOptions.targetCompatibility.
kotlin {
    compilerOptions {}
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Media3 Transformer: the hardware conversion path (MediaCodec decode -> GL surface
    // -> MediaCodec encode). Apache-2.0, so no licensing exposure.
    implementation(libs.media3.transformer)
    implementation(libs.media3.effect)
    implementation(libs.media3.common)
    implementation(libs.media3.muxer)

    // FFmpeg, built from source by tools/ffmpeg. Not on any Maven repo: ffmpeg-kit was
    // archived and delisted, and ffmpeg-kit-next is source-only by design.
    // The AAR is gitignored; see app/libs/README.md to produce it.
    implementation(files("libs/ffmpeg-kit-next-8.1.1.aar"))
    // A local .aar carries no transitive dependencies, so the wrapper's own runtime
    // dependency has to be declared here explicitly.
    implementation(libs.smart.exception.java)

    // Durable job queue. WorkManager survives process death, which is what makes the
    // queue resumable after the foreground-service timeout fires.
    implementation(libs.androidx.work.runtime.ktx)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.windowsize)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.work.testing)
    debugImplementation(libs.compose.ui.test.manifest)
}
