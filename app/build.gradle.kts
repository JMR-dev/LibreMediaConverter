import org.gradle.api.tasks.PathSensitivity
import org.gradle.testing.jacoco.tasks.JacocoReport
import java.io.File
import java.time.Duration

plugins {
    // Applied by id: these two come from the root buildscript classpath, which is what
    // overrides AGP's bundled Kotlin. See the comment in the root build file.
    id("com.android.application")
    // Required even under AGP 9: the Compose compiler plugin is NOT built in.
    id("org.jetbrains.kotlin.plugin.compose")

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
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
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

    testOptions {
        unitTests {
            // Robolectric needs the merged manifest and the compiled resource table to build
            // an Android runtime on the JVM. Without this, AGP hands the unit tests a stub
            // android.jar with no resources and Robolectric cannot start.
            isIncludeAndroidResources = true

            all {
                // Robolectric's native runtime calls System.load(), which Java 25 reports as
                // a restricted method -- four lines of warning on every test run, and a hard
                // failure in some later JDK. Granting it explicitly says the native access is
                // known and wanted rather than leaving the JVM to guess.
                it.jvmArgs("--enable-native-access=ALL-UNNAMED")
            }
        }
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

// --- Prerelease guard for the floating dependency versions ------------------------------
// The library versions in libs.versions.toml float on minor + patch ("1.+"). Gradle resolves
// `+` to the highest version it finds, and it does NOT skip prereleases -- so without this,
// androidx would quietly hand the app an alpha. That is not hypothetical here: at the time
// of writing lifecycle, navigation, work, datastore and annotation ALL publish an alpha or
// rc numbered above their newest stable, so five of the floats would have moved onto
// unreleased code on the next build, with nothing in the diff to say so.
//
// Rejecting them here means "+" reads as "the newest RELEASED version", which is what
// floating was meant to buy.
//
// Note that this applies to STATIC versions too, not only floating ones: naming
// "2.12.0-alpha01" in the catalog does not get you that alpha, it fails to resolve. Verified,
// because the obvious assumption is the opposite. To take an androidx prerelease deliberately,
// drop the group from the guarded list below for as long as you need it.
// Groups whose versions this project actually floats. The guard applies to these and to
// nothing else, which is the whole point.
//
// The first version of this was a blanket rule over every group, and it broke every E2E job
// while every local check stayed green. AGP resolves its OWN tooling through this project's
// configurations, and the Unified Test Platform that runs connectedAndroidTest depends on
// com.google.testing.platform artifacts pinned at 0.0.9-alpha04. Rejecting those made
// :app:connectedDebugAndroidTest unresolvable. Nothing that runs without a device touches
// that configuration, so it passed here and failed on all four API levels at once.
//
// Scoping to the groups we float is also why detekt's alpha needs no exception any more: we
// do not float dev.detekt, so the guard has no opinion about it. An allowlist of exceptions
// would have needed a new entry every time AGP pulled in another prerelease tool.
val floatedGroupPrefixes = listOf("androidx.", "junit", "com.arthenica")

// Matches both spellings androidx and friends use: "-alpha01" and "-alpha.1".
val prereleaseMarker =
    Regex("""[-.](alpha|beta|rc|eap|dev|snapshot|pre|m)[-.]?\d*$""", RegexOption.IGNORE_CASE)

configurations.configureEach {
    resolutionStrategy {
        componentSelection {
            all {
                val floated = floatedGroupPrefixes.any { candidate.group.startsWith(it) }
                if (floated && prereleaseMarker.containsMatchIn(candidate.version)) {
                    reject("prerelease; floating versions take released builds only")
                }
            }
        }
    }
}

detekt {
    // Merge the project overrides in config/detekt onto detekt's bundled defaults, so this
    // repo's file only has to carry the rules it actually changes.
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
}

// Pin the coverage agent rather than inheriting whatever Gradle bundles.
// Robolectric loads every class it touches through its own sandbox classloader, and those
// classes arrive with no source location. JaCoCo skips no-location classes by default, so
// without this block **not one Robolectric test counts** -- and Robolectric is what exercises
// the framework edge here: the workers, the publisher, both ViewModels, every Compose screen.
//
// Measured on e06b082, same 335 tests, same 0 failures, only this block added:
//
//     LINE    29.7% -> 69.2%        OutputPublisher      0.0% -> 97.5%
//     BRANCH  29.8% -> 53.2%        ConversionViewModel  0.0% -> 85.4%
//
// The discriminator, if this ever looks like superstition: inside ConverterScreenKt, `describe`
// is the one non-Composable and is exercised by a plain JVM test -- it reported 8/8 covered while
// every @Composable in the same class reported 0, including ones whose mutations demonstrably
// failed the build when reverted.
//
// `excludes` is not optional. Without it JaCoCo walks JDK-internal classes that Robolectric has
// no location for either, and the test JVM dies rather than reporting a number.
tasks.withType<Test>().configureEach {
    // ReleasePermissionTest reads .github/workflows/build.yml, and Gradle cannot infer that a
    // test depends on a file outside the source set. Without this the task stays UP-TO-DATE
    // when the workflow changes, so the guard goes stale exactly when it matters. Measured:
    // deleting the release job's `contents: write` and re-running gave "BUILD SUCCESSFUL in
    // 614ms" with the test never executing; the same mutation under --rerun-tasks failed it.
    // A guard that does not re-run when its subject changes is not a guard.
    inputs.file(rootProject.file(".github/workflows/build.yml"))
        .withPropertyName("releaseWorkflow")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // Same reasoning, same trap: HangBoundTest reads the two numbers below out of this file, and
    // they are the one part of the change that does not compile. Without this the task stays
    // UP-TO-DATE when the build script changes, so the guard would go stale on exactly the edit
    // it exists to catch.
    inputs.file(project.file("build.gradle.kts"))
        .withPropertyName("moduleBuildScript")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    // --- Bounding a hung run (#125) -----------------------------------------------------------
    //
    // This suite had no timeout of any kind, so a hang ran until something outside it gave up.
    // #125 is a real Java-level deadlock -- a lock-order inversion between Room's
    // TransactionExecutor and WorkManager's SerialExecutorImpl, reached through the WorkInfo flow
    // -- and one local run sat in it for 47 minutes. On CI it would burn the Unit tests job's
    // 30-minute cap and report as a job timeout with no cause at all.
    //
    // WHY NOT A JUnit `Timeout` RULE, which is the obvious answer: it runs the test body on a
    // separate thread, and this suite is thread-affine. Measured here, `@Rule Timeout` and
    // `@Test(timeout = ...)` against a `createComposeRule()` Robolectric test both give:
    //
    //     java.lang.UnsupportedOperationException: main looper can only be controlled from main
    //       at org.robolectric.shadows.ShadowPausedLooper.executeOnLooper
    //       at androidx.compose.ui.test.RobolectricIdlingStrategy.runUntilIdle
    //
    // The same two tests with the timeout removed pass, so that is the mechanism and not the
    // probe. Nothing that moves a test off its own thread can be used here.
    //
    // `Task.timeout` moves nothing -- it stops the forked test JVM from outside. Its weakness is
    // that it kills without a thread dump, and the jstack is the only reason #125 could be named
    // at all; the watchdog below is what answers that, and it only dumps.
    //
    // THE NUMBER, against the slowest observed *pass* rather than the typical one. Eight CI runs
    // sampled 2026-08-26, whole `./gradlew :app:testDebugUnitTest` invocation with compilation in
    // it and this task a subset: 62, 76, 77, 79, 81, 84, 86 and 90 seconds. Locally the task
    // itself is ~11 s over 454 tests. Ten minutes is ~6.7x the slowest of those and a third of
    // the job's 30-minute cap, so a fired timeout still has room to be reported and uploaded. It
    // is deliberately nowhere near the observed duration: a timeout that fires on a healthy slow
    // runner turns a real signal into noise and teaches people to re-run reflexively.
    timeout.set(Duration.ofMinutes(10))

    // The dump, two minutes before the kill. jstack is what turned #125 from "CI timed out" into
    // a named lock-order inversion, and `Task.timeout` on its own would have thrown it away.
    //
    // It is deliberately incapable of failing a build: it reads a live process and writes a file.
    // Nothing here kills, interrupts or signals anything, so the worst a misfire can do is leave a
    // stack trace nobody needed. It has one, and it is the ordinary CI shape rather than an exotic
    // case: the worker is found by scanning this daemon's descendants for GradleWorkerMain, which
    // cannot tell one invocation's worker from the next, and the Unit tests job runs
    // testDebugUnitTest and jacocoTestReport back to back against the same daemon. If this task's
    // own worker lived and died inside a single poll, the watchdog can adopt the following one.
    //
    // Everything it needs is read here, at configuration time, and captured by value. Reaching
    // back through the task or the project from inside the action would not survive the
    // configuration cache, which `gradle.properties` turns on for every build.
    val threadDump = layout.buildDirectory.file("reports/hang/$name-threads.txt").get().asFile
    val taskPath = path
    val dumpAfterNanos = Duration.ofMinutes(8).toNanos()
    val captureWindowNanos = Duration.ofMinutes(1).toNanos()
    val pollMillis = 1_000L
    doFirst {
        val watchdog = Thread {
            val startedAt = System.nanoTime()
            var worker: ProcessHandle? = null
            while (true) {
                Thread.sleep(pollMillis)
                val elapsed = System.nanoTime() - startedAt
                val watched = worker
                if (watched == null) {
                    // Gradle forks the worker moments after this task starts. If none has shown
                    // up by the end of the capture window there is nothing to watch, and going on
                    // polling would only risk adopting some other build's.
                    if (elapsed > captureWindowNanos) return@Thread
                    worker = ProcessHandle.current().descendants()
                        .filter { it.info().commandLine().orElse("").contains("GradleWorkerMain") }
                        .findFirst().orElse(null)
                } else if (!watched.isAlive) {
                    return@Thread // the run finished; this is the healthy exit
                } else if (elapsed >= dumpAfterNanos) {
                    val jstack = File(File(System.getProperty("java.home"), "bin"), "jstack")
                    threadDump.parentFile.mkdirs()
                    if (jstack.canExecute()) {
                        ProcessBuilder(jstack.absolutePath, "-l", watched.pid().toString())
                            .redirectErrorStream(true)
                            .redirectOutput(threadDump)
                            .start()
                            .waitFor()
                    } else {
                        threadDump.writeText("no jstack at ${jstack.absolutePath}\n")
                    }
                    // To stdout as well as to the file, and that is the half that matters on CI:
                    // the Unit tests job uploads app/build/reports/tests/ and nothing else, so a
                    // dump that only ever existed under reports/hang/ would be unreachable from a
                    // red run -- which is the "timed out with no cause" this exists to end. The
                    // step log always survives, and needs no workflow edit to say so.
                    println(
                        "$taskPath is still running after ${Duration.ofNanos(elapsed).toMinutes()} " +
                            "minutes and is about to be timed out. Thread dump of pid " +
                            "${watched.pid()}, also written to $threadDump -- look for 'Found one " +
                            "Java-level deadlock' (that is #125).\n" + threadDump.readText(),
                    )
                    return@Thread
                }
            }
        }
        watchdog.isDaemon = true
        watchdog.name = "hang-watchdog"
        watchdog.start()
    }

    extensions.configure<JacocoTaskExtension> {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}

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
// classic `tmp/kotlin-classes/debug`. All hand-written code in the MAIN source set is Kotlin, so
// the javac output (BuildConfig and R only) is not read at all. There is now one hand-written
// Java file in the module -- androidTest's FixtureDocumentsProvider, which cannot be Kotlin
// because the process it runs in has no Kotlin stdlib; its own header explains why. It is in
// androidTest, so it is not in this task's classDirectories and this stays accurate.
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
    // An Android runtime on the JVM. Everything else in src/test is a pure function; this is
    // here for the one thing a pure function cannot assert -- that a staged file is really
    // gone from a real cacheDir. Instrumented tests do not run on the development host, so
    // without it that assertion could only be written where nobody can execute it.
    testImplementation(libs.robolectric)
    // Already in the catalog for androidTest, and already inside the prerelease guard via its
    // androidx. group. WorkManagerTestInitHelper + SynchronousExecutor are what let a JVM test
    // drive a ViewModel through a real WorkManager to SUCCEEDED, which is where the cleanup
    // handle is set -- the wiring the leak actually lived in.
    testImplementation(libs.androidx.work.testing)
    // Compose's own test rules, on the JVM source set as well as androidTest. Already in the
    // catalog, already inside the prerelease guard via its androidx. group, and versioned by
    // the BOM, so this adds no new pinning argument.
    //
    // Here rather than only in androidTest because ui-test-junit4 runs under Robolectric:
    // createComposeRule() drives a real composition on the JVM. The defect it was added for
    // -- the selected tab not surviving recreation -- is caught by StateRestorationTester,
    // and putting that test where the instrumented suite lives would mean nobody on this
    // host could ever watch it go red.
    //
    // ui-test-manifest is deliberately NOT repeated here. It supplies the ComponentActivity
    // the rule launches, and the debugImplementation entry below already puts it in the
    // merged manifest the unit tests build against -- checked by removing it and watching
    // the tests stay green.
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    // For `runTest` alone, in ConversionViewModelProbeFailureTest. It arrives transitively
    // with the rule above anyway; declared because a test file imports it directly, and an
    // import of something nobody asked for breaks the day the library that pulled it in stops.
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.work.testing)
    // androidTest only, and it has to be: UiAutomator drives the whole device, including
    // windows belonging to other packages. The system file picker is one -- DocumentsUI runs
    // in its own process, so Compose's matchers cannot see it and Espresso's cannot either
    // (both are scoped to this process's view hierarchy). Nothing on the JVM has a device to
    // drive, so there is no unit-test counterpart to add it to.
    androidTestImplementation(libs.androidx.uiautomator)
    debugImplementation(libs.compose.ui.test.manifest)
}
