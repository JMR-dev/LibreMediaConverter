# API 37 is not tested in CI: a crash in Google's `android-37.0` emulator image

**Status:** open upstream, worked around by removing API 37 from the E2E matrix.
The app itself is verified good on real API 37 hardware — this is an emulator bug only.
**Last verified:** 2026-08-21, against emulator `37.1.11.0` and system image revision 6

`minSdk` is 33 and `targetSdk` is 37, and the E2E matrix in
[`status_check.yml`](../.github/workflows/status_check.yml) runs API 33 through 36.
API 37 is deliberately absent. This is why.

## Summary

The `android-37.0` emulator system image crashes `surfaceflinger` in a loop. The app
under test never gets a working framework, so every instrumented test fails regardless
of what the app does. The bug is in the emulator image, not in this project.

The crash is an assertion inside the emulator's own gralloc implementation:

```
Executable:    /system/bin/surfaceflinger
signal 6 (SIGABRT), code -1 (SI_QUEUE), tid: RegionSampling
Abort message: 'Assertion failed: !rcEnc->featureInfo()->hasReadColorBufferDma'

  #03  mapper.ranchu.so   GoldfishMapper::readFromHost(cb_handle_t const&) const
  #04  mapper.ranchu.so   GoldfishMapper::GoldfishMapper()::'lambda'(...)::__invoke
  #05  libui.so           android::Gralloc5Mapper::lock(...)
  #06  libui.so           android::GraphicBufferMapper::lock(...)
  #07  libui.so           android::GraphicBuffer::lockAsync(...)
  #08  libui.so           android::GraphicBuffer::lock(...)
  #09  surfaceflinger     android::RegionSamplingThread::threadMain()
```

`RegionSamplingThread` is SystemUI's navigation-bar luma sampling. It calls
`GraphicBuffer::lock`, which routes into `GoldfishMapper::readFromHost`, which asserts
that the host has *not* negotiated the `ReadColorBufferDma` capability. On this image
the host has, so the assertion fails and `surfaceflinger` aborts. It restarts and
aborts again.

## Impact

The failure surfaces in two different ways depending on how far the job gets, which is
why it took several rounds to identify:

| Guest RAM | Where it dies | What CI reports |
|---|---|---|
| 1536 MB | during APK install | `Unknown failure: cmd: Can't find service: package` |
| 2560 MB | during the test run | `There were failing tests` — all of them |

At 2560 MB the install succeeds and the tests actually execute, then fail wholesale.
The first failure in the report is misleading:

```
kotlin.UninitializedPropertyAccessException: lateinit property output has not
    been initialized
    at Media3EngineTest.tearDown(Media3EngineTest.kt:53)

java.lang.IllegalStateException: WorkManager is not initialized properly.
    You have explicitly disabled WorkManagerInitializer in your manifest, ...
```

Neither is a real defect in this project. `tearDown` throws because `setUp` never got
far enough to assign `output`, and WorkManager's `InitializationProvider` never runs
because content-provider installation fails on a framework whose `surfaceflinger` is
crash-looping. The same tests pass at API 33, 34, 35, and 36 in the same CI run, and
the first `surfaceflinger` abort is timestamped *before* the test results are reported.

This is not inference. The full suite was run against a physical API 37 device and
passed — see [Verified on real API 37 hardware](#verified-on-real-api-37-hardware)
below. `ConversionWorkerTest` and `ConcatWorkerTest`, which drive a real WorkManager
round trip and are among the tests that failed this way in CI, both pass there.

## Environment

Reproduced identically in two unrelated environments, so it is not specific to a host
GPU, driver, or CI runner.

| | GitHub Actions | Local workstation |
|---|---|---|
| Host | `ubuntu-latest`, no GPU | Fedora, Intel Iris Xe (TGL GT2) |
| Emulator | `37.1.11.0` (build 15917651) | `37.1.11.0` (build 15917651) |
| GPU mode | `swiftshader_indirect` | `host` |
| Result | boots, aborts during tests | aborts before boot completes |

System image: `system-images;android-37.0;google_apis;x86_64`, `Pkg.Revision=6`,
`AndroidVersion.ApiLevel=37.0`, `AndroidVersion.ExtensionLevel=22`

```
Build fingerprint: google/sdk_gphone64_x86_64/emu64xa:17/CE2A.260420.019/15611780:userdebug/dev-keys
Kernel Release:    6.12.58-android16-6-gccafb60de224-ab14828483
```

## What was ruled out, and how

Each of these was tested rather than reasoned about, because the first three attempts
at this bug were plausible fixes that turned out to address earlier, unrelated failures.

**Guest memory.** The emulator raises an undersized guest to a minimum on its own, but
only for API levels it recognises, and it does not recognise `"37.0"`. API 33 bumps to
2048 MB and 34/35/36 to 2560 MB, while API 37 logged no bump at all and ran at the
`pixel_6` default of 1536 MB. Setting `ram-size: 2560M` explicitly fixed that asymmetry
and did change the outcome — the job got past install and into the test run — but it is
not the underlying bug. At the moment of failure the guest reported `MemTotal 2527392
kB` with `MemAvailable 1507104 kB`: 1.5 GB free, and no OOM kills.

**GPU mode.** Both `swiftshader_indirect` and `host` crash, with the same assertion and
the same frames. The crash is in the gralloc mapper, below the renderer.

**Disabling the DMA feature.** `GLDMA` is the host feature that most plausibly backs the
guest's `hasReadColorBufferDma`. Launching with `-feature -GLDMA` was accepted by the
emulator — the log confirms `Feature 'GLDMA' (51) is overridden to 'disabled'` — and
`surfaceflinger` still aborted 13 times and the device never finished booting. Whatever
sets that guest capability, it is not this flag.

**An ATD image.** `google_atd` / `aosp_atd` images are built for automated testing and
ship without the SystemUI package set, which is what drives `RegionSamplingThread` in
the first place. That would likely sidestep the bug class entirely, but **no ATD image
exists for `android-37.0`** — only `google_apis`, `google_apis_playstore`, the `ps16k`
16 KB-page variants, and Wear OS. Check again when revisiting; if an ATD image appears,
try it before anything else here.

## Verified on real API 37 hardware

The bug is confined to the emulator image. On 2026-08-21 the whole instrumented suite
was run against a physical device and passed:

```
Device:  Pixel 10 Pro XL (mustang), arm64-v8a
Build:   google/mustang/mustang:17/CP2A.260805.005/15828068:user/release-keys
API:     37 (Android 17, codename REL -- a release build, not a preview)

./gradlew :app:connectedDebugAndroidTest -PabiFilters=arm64-v8a
  40 tests, 0 failures, 0 errors, 2 skipped        BUILD SUCCESSFUL
```

The two skips are `RealMediaBenchmark.hardwareVersusSoftwareOnRealVideo` and
`av1InputRoutesAccordingToDeviceDecodeSupport`, which `assumeTrue` their sample files
are present and skip when they are not. That is by design and unrelated to API level.

One harmless warning appears during the run and can be ignored:
`No UID for androidx.test.services in user 0`, from an `appops` call the test services
package makes before it is fully registered.

So the app is correct on Android 17. What is missing is only *automated* coverage in
CI. Until the image is fixed, run the suite on a physical API 37 device before release;
that is the substitute for the missing matrix row.

## Reproducing it

Locally, with `-gpu host` so the emulator itself does not segfault on Intel graphics:

```bash
sdkmanager --install "system-images;android-37.0;google_apis;x86_64"
echo no | avdmanager create avd -n api37_repro \
    -k "system-images;android-37.0;google_apis;x86_64" -d pixel_6 --force

$ANDROID_HOME/emulator/emulator -avd api37_repro \
    -no-window -gpu host -noaudio -no-boot-anim -camera-back none -no-snapshot &

# Boot never completes. Count the aborts:
adb logcat -d -b crash | grep -c hasReadColorBufferDma
```

`sys.boot_completed` never reaches `1`, `pgrep -f system_server` stays empty, and
`keystore2`'s watchdog logs `await_boot_completed ... Overdue` indefinitely.

To see the CI-side form instead, restore the API 37 row in the E2E matrix of
`status_check.yml` (`api-level: "37.0"` — a bare `37` fails earlier still, during SDK
setup, because there is no `platforms;android-37`).

## Filing this upstream

Not yet filed. To file it:

1. Go to <https://issuetracker.google.com/> and sign in with a Google account.
2. Choose **Report an issue**, then pick the component for the Android emulator — search
   the component picker for "Emulator"; it sits under the Android Studio component tree.
   If the picker is unclear, Android Studio's **Help → Submit Feedback** opens the same
   tracker with the component preselected, and the emulator's own **Extended controls →
   Help → File a bug** does likewise.
3. Title it for the mechanism, not the symptom, so it is searchable — for example:
   `surfaceflinger aborts in GoldfishMapper::readFromHost (hasReadColorBufferDma) on
   android-37.0 google_apis x86_64`.
4. Paste the assertion and backtrace from the top of this document, the environment
   table, and the reproduction steps above. State explicitly that it reproduces on two
   unrelated hosts under both GPU modes — that is the detail that stops it being closed
   as a local graphics problem.
5. List what was ruled out. Bugs that arrive with `-feature -GLDMA` already eliminated
   tend not to bounce back asking for it.
6. Attach:
   - the guest tombstone, via `adb pull /data/tombstones` (or the `pbtombstone` output
     the crash log names)
   - `adb logcat -d -b crash > crash.txt`
   - the emulator's own stdout log, captured by redirecting the launch command
   - the AVD's `config.ini`
   - a link to a failing CI job, which shows it on hardware you do not control:
     <https://github.com/JMR-dev/LibreMediaConverter/actions/runs/32545625459/job/96963461184>

Record the issue number here once filed.

## When to revisit

Re-add the API 37 row when any of these happens:

- a new `android-37.0` system image revision ships (this was revision 6)
- an ATD image appears for API 37
- the upstream issue is marked fixed

Until then the gap is narrower than the missing row suggests. `targetSdk` is 37, so the
app is compiled and unit-tested against it; the API-dependent behaviour this matrix
exists to exercise — the foreground service type, absent below 34, `dataSync` at 34,
`mediaProcessing` from 35 — is covered at 35 and 36; and the full instrumented suite has
been run green on real API 37 hardware. What is missing is *automated* API 37 coverage,
so a regression there would not be caught by a pull request. Run the suite on a physical
API 37 device before each release for as long as this row is absent.
