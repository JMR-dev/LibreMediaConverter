import org.gradle.testing.jacoco.tasks.JacocoReport

plugins {
    alias(libs.plugins.android.application)
    // Required even under AGP 9: the Compose compiler plugin is NOT built in.
    alias(libs.plugins.kotlin.compose)

    // Lint/format. Resolved from the Gradle Plugin Portal, not AGP's buildscript
    // classpath -- neither is an Android plugin.
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)

    // JaCoCo (Gradle built-in) instruments the JVM testDebugUnitTest task. Report only:
    // there is deliberately no coverage gate, see the jacocoTestReport block below.
    jacoco
}

android {
    namespace = "org.libremediaconverter"

    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "org.libremediaconverter"
        minSdk = 33
        // AGP 9 defaults targetSdk to compileSdk, so always state it explicitly.
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 64-bit only. The 16 KB page-size rules apply to these ABIs; 32-bit is not
        // required by Play and would add ~2 more copies of the FFmpeg .so files.
        //
        // Overridable so a test run can build for just the ABI it will execute on.
        // FFmpeg's native libraries dominate the APK, so shipping both ABIs to an
        // x86_64 emulator doubles it for no benefit -- and on API 37, whose system
        // image leaves less free userdata, the full APK does not fit at all:
        // "Requested internal only, but not enough space".
        //
        //   ./gradlew :app:connectedDebugAndroidTest -PabiFilters=x86_64
        //
        // Release builds ignore this and always ship both.
        ndk {
            val requested = (findProperty("abiFilters") as String?)
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
            abiFilters += requested ?: listOf("arm64-v8a", "x86_64")
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
        sourceCompatibility = JavaVersion.VERSION_24
        targetCompatibility = JavaVersion.VERSION_24
    }

    lint {
        // ktlint and detekt both fail the build on any finding. Android lint by default
        // aborts on errors only, so warnings would land in the report while the gate stayed
        // green -- a gate that passes while the report has content is not a gate.
        warningsAsErrors = true
        // Already the default. Stated so a later edit cannot turn the gate off by accident.
        abortOnError = true

        // Dependency-freshness nags. These do not describe this code: they go red the day
        // someone else publishes a release, which would turn a PR red for a reason its
        // author cannot see in their own diff and cannot fix by changing anything they
        // wrote. They also want the network at lint time. Upgrades are a deliberate act
        // here -- the Kotlin version in particular is pinned to AGP's bundled KGP and is
        // NOT free to follow the newest release -- so they are chosen, not nagged for.
        disable += setOf("AndroidGradlePluginVersion", "NewerVersionAvailable", "GradleDependency")

        // A real suggestion, deliberately not acted on in this commit. hasSpaceFor() reads
        // File.usableSpace, which under-reports because it ignores cache the system could
        // reclaim -- so the app can refuse a conversion it actually had room for.
        // StorageManager.getAllocatableBytes is the better answer, but swapping it in
        // changes when a job is rejected and can throw IOException, which is a behaviour
        // change to a safety check and deserves its own commit and its own test rather
        // than a drive-by in a tooling change. `informational` keeps it visible in every
        // lint report instead of hiding it, while letting the gate pass until then.
        informational += "UsableSpace"
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

detekt {
    // Merge the project overrides in config/detekt onto detekt's bundled defaults, so this
    // repo's file only has to carry the rules it actually changes.
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
}

// Pin the coverage agent rather than inheriting whatever Gradle bundles.
jacoco {
    toolVersion = libs.versions.jacoco.get()
}

// --- Unit-test coverage -------------------------------------------------------------------
// Report only. There is deliberately no coverage gate: a floor is only meaningful against a
// measured baseline, and the JVM test stack here is still junit-only. This task produces the
// number a floor would need; add `jacocoTestCoverageVerification` once it is known.

// Generated code, stripped from the denominator so the percentage reflects hand-written Kotlin.
// No DI framework is in use, so there are no Hilt/Dagger patterns to exclude.
val jacocoGeneratedExcludes = listOf(
    "**/R.class",
    "**/R\$*.class",
    "**/BuildConfig.*",
    "**/Manifest*.*",
    // Room lands in a later phase; its KSP output comes out the same door as hand-written code.
    "**/*_Impl*",
    // One per file with @Composable lambdas -- Compose compiler output, not written by anyone.
    "**/ComposableSingletons*",
)

// AGP 9 compiles Kotlin through its built-in compiler, which writes here rather than to the
// classic `tmp/kotlin-classes/debug`. All hand-written code in this module is Kotlin, so the
// javac output (BuildConfig and R only) is not read at all.
val jacocoDebugKotlinClasses = layout.buildDirectory.dir(
    "intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes",
)

// Accept both the base `jacoco` plugin's default exec location and AGP's
// enableUnitTestCoverage one, so the wiring survives either being the source of truth.
val jacocoExecutionData = fileTree(layout.buildDirectory) {
    include(
        "jacoco/testDebugUnitTest.exec",
        "outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec",
    )
}

tasks.register<JacocoReport>("jacocoTestReport") {
    // The exec data does not exist until the tests have run.
    dependsOn("testDebugUnitTest")
    group = "verification"
    description = "Generates JaCoCo XML + HTML coverage for the debug JVM unit tests."

    reports {
        xml.required.set(true)
        html.required.set(true)
    }

    classDirectories.setFrom(fileTree(jacocoDebugKotlinClasses) { exclude(jacocoGeneratedExcludes) })
    sourceDirectories.setFrom(files("src/main/java"))
    executionData.setFrom(jacocoExecutionData)
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

    // FFmpeg, committed under bin/. Not on any Maven repo: ffmpeg-kit was archived and
    // delisted, and ffmpeg-kit-next is source-only by design.
    //
    // The prebuilt archive is checked in on purpose. Rebuilding it per CI run made test
    // results ambiguous -- a red build could mean broken code or a cross-compile that
    // hiccuped. See bin/README.md for provenance and how to regenerate it.
    implementation(files(rootProject.file("bin/ffmpeg-kit-next-8.1.1.aar")))
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
