# API 37 on the emulator: a guest gralloc bug that only the host GL renderer triggers

**Status:** the bug is real and still open upstream, but the previous diagnosis in this file was
wrong about its most important detail. **The renderer decides whether API 37 boots**, and once it
boots, disabling SystemUI stops the crashes entirely. `tools/local-emulator/run-e2e.sh 37` now
runs the suite locally: **49 tests, 2 failures, 0 errors, 2 skipped**. CI's matrix should still
stop at 36 — see [So should CI take API 37?](#so-should-ci-take-api-37).
**Last verified:** 2026-08-22, emulator `37.1.11.0` (build 15917651), Fedora 44,
against system images `android-37.0` rev 6 **and** `android-37.1` rev 8.

## The correction

This file previously said, under "What was ruled out":

> **GPU mode.** Both `swiftshader_indirect` and `host` crash, with the same assertion and
> the same frames. The crash is in the gralloc mapper, below the renderer.

**That is wrong.** The mapper is below the renderer, but *whether the mapper's bad path is
reached* is not. Re-measured on 2026-08-22, seven runs, one variable at a time:

| # | system image | `-gpu` | GLES the emulator chose | booted? | surfaceflinger aborts |
|---|---|---|---|---|---|
| r01 | `android-37.0` rev 6 | `host` | host (Mesa Iris Xe) | **no**, 422 s | 71, looping |
| r02 | `android-37.1` rev 8 | `host` | host (Mesa Iris Xe) | **no**, 362 s | 65, looping |
| r03 | `android-37.0` rev 6 | `swangle_indirect` | ANGLE | **yes, 85 s** | 1 |
| r04 | `android-37.0` rev 6 | `host` + `-feature -GLDMA,-GLDMA2,-GLDirectMem` | host | **no**, 363 s | 57, looping |
| r05 | `android-37.0` rev 6 | `angle_indirect` | ANGLE | **yes, 112 s** | 2 |
| r06 | `android-37.1` rev 8 | `swangle_indirect` | ANGLE | **yes, 285 s** | 23 |
| r07 | `android-37.0` rev 6 | `host` + `-feature -HostComposition` | host | **no**, wedged adb at 208 s | not readable |

The discriminator is exact across all seven: **a run boots if and only if the emulator log says
something other than `gles_mode_selected:host`.**

```
# r01, r02, r04, r07 -- never boots
INFO | emuglConfig_init: vulkan_mode_selected:host gles_mode_selected:host
INFO | Graphics Adapter Android Emulator OpenGL ES Translator (Mesa Intel(R) Iris(R) Xe Graphics (TGL GT2))

# r03, r05, r06 -- boots
INFO | emuglConfig_init: vulkan_mode_selected:swiftshader gles_mode_selected:swangle
INFO | Graphics Adapter Android Emulator OpenGL ES Translator (ANGLE (Google, Vulkan 1.2.0
     | (SwiftShader Device (Subzero) (0x0000C0DE)), SwiftShader driver-5.0.0))
```

### Why the wrong claim looked right

It rested on two samples of two different things, and neither of them was ANGLE.

- The **local** `swiftshader_indirect` sample was void. On this workstation *every*
  SwiftShader-GLES launch segfaults the host emulator before the guest matters at all —
  SELinux denies `execheap` to SwiftShader's Reactor JIT. That is
  [`docs/local-emulator.md`](local-emulator.md), and it was not yet understood when this file
  was written. So "`swiftshader_indirect` crashes" was true, for an entirely unrelated reason,
  and told you nothing about the gralloc assertion.
- The **CI** sample was one `swiftshader_indirect` run on a GPU-less `ubuntu-latest`, and the
  **local** sample was one `-gpu host` run. Two renderers, one measurement each, and the pair
  written up as "both GPU modes".

`angle_indirect` and `swangle_indirect` — the two modes that work — had never been tried on
API 37. Neither had a second system image.

The lesson is the same one `docs/local-emulator.md` ends on, which makes it worth repeating:
"both backends fail" is a claim about a matrix, and a matrix needs cells, not inference. Two
observations of two different configurations do not establish anything about a third.

## What the bug actually is

`surfaceflinger` aborts inside the emulator's own gralloc mapper:

```
Executable:    /system/bin/surfaceflinger
signal 6 (SIGABRT), code -1 (SI_QUEUE), tid: RegionSampling
Abort message: 'Assertion failed: !rcEnc->featureInfo()->hasReadColorBufferDma'

  #03  /vendor/lib64/hw/mapper.ranchu.so  GoldfishMapper::readFromHost(cb_handle_t const&) const+543
  #04  /vendor/lib64/hw/mapper.ranchu.so  GoldfishMapper::GoldfishMapper()::'lambda'(...)::__invoke+704
  #05  /system/lib64/libui.so             android::Gralloc5Mapper::lock(...)+63
  #06  /system/lib64/libui.so             android::GraphicBufferMapper::lock(...)+198
  #07  /system/lib64/libui.so             android::GraphicBuffer::lockAsync(...)+545
  #08  /system/lib64/libui.so             android::GraphicBuffer::lock(...)+67
  #09  /system/bin/surfaceflinger         android::RegionSamplingThread::threadMain()+2571
```

`RegionSamplingThread` is SystemUI's nav-bar luma sampling. It locks a `GraphicBuffer` for CPU
read; that routes through the Gralloc5 mapper into `GoldfishMapper::readFromHost`, which is the
*non-DMA* readback path and asserts that the host has not negotiated `ReadColorBufferDma`. The
host always has, so the assert fires whenever that path is taken.

Two facts pin down what "always" means:

- **The capability is negotiated regardless of renderer.** The abort fires under ANGLE too
  (r03/r05/r06), just far less often. `ANDROID_EMU_read_color_buffer_dma` lives in
  `emulator/lib64/libgfxstream_backend.so`, which every `-gpu` mode goes through — it is the
  only file in the whole SDK that contains the string.
- **It is not gated by any feature flag the emulator exposes.** See the ruled-out list below.

So the renderer does not decide whether the guest *believes* DMA readback exists. It decides how
often `RegionSamplingThread` ends up in `readFromHost` — which under the host GL translator is
constantly, and under ANGLE is occasionally.

### Why one abort takes down the whole device

`surfaceflinger` is a critical service. When it dies, `init` kills the framework with it:

```
08-22 21:40:28.253 I/init: Sending SIGKILL to service 'zygote' (pid 470) process group...
08-22 21:40:28.260 I/init: Service 'zygote' (pid 470) received SIGKILL
```

Everything above zygote goes with it, which is why the symptoms look nothing like a graphics
bug. Under `-gpu host` the cycle repeats every five to seven seconds forever and
`sys.boot_completed` is never set. Under ANGLE the aborts are sparse enough that the boot
usually completes between them — but they do not stop, and each one is a framework restart.
That is the difference between "boots" and "is usable", and it is the reason this is not simply
fixed by changing the renderer. See [Can the suite run on it?](#can-the-suite-run-on-it) below.

## Environment

| | GitHub Actions | Local workstation |
|---|---|---|
| Host | `ubuntu-latest`, no GPU | Fedora 44, Intel Iris Xe (TGL GT2), kernel `7.1.8-200.fc44` |
| Emulator | `37.1.11.0` (build 15917651) | `37.1.11.0` (build 15917651) |
| GPU mode measured | `swiftshader_indirect` | `host`, `angle_indirect`, `swangle_indirect` |

Images, both reproducing it:

```
system-images;android-37.0;google_apis;x86_64          Pkg.Revision=6   ApiLevel=37.0  ExtensionLevel=22
  fingerprint google/sdk_gphone64_x86_64/emu64xa:17/CE2A.260420.019/15611780:userdebug/dev-keys
system-images;android-37.1;google_apis_ps16k;x86_64    Pkg.Revision=8   ApiLevel=37.1  ExtensionLevel=23
  ro.build.version.codename=REL   (a release image, not a preview)
```

## What was ruled out, and how

**A newer system image.** This file's own revisit trigger was "a new `android-37.0` system image
revision ships (this was revision 6)". That trigger was written too narrowly and would never have
fired: `android-37.0` is *still* revision 6, but Google shipped a whole new minor level.
`android-37.1` `google_apis_ps16k` revision 8 — a `REL` build, not a beta — was installed and
tested (r02, r06) and **behaves identically**: same assertion, same frames, never boots under
`-gpu host`, and *worse* under ANGLE (23 aborts to `37.0`'s 1). `android-37.2-beta3` exists too
but was not needed; two independent images agreeing settles it, and a beta could not be used by
CI anyway.

**An ATD image.** Still does not exist for API 37. `sdkmanager --list` offers `aosp_atd` and
`google_atd` for API 30 through 36 and nothing above:

```
system-images;android-36;google_atd;x86_64 | 1 | Google APIs ATD Intel x86_64 Atom System Image
(no android-37 ATD of any kind)
```

For API 37 the only x86_64 images are `google_apis`, `google_apis_playstore`, their `ps16k`
16 KB-page variants, and Wear OS. Check again when revisiting.

**The DMA feature flags.** `GLDMA` alone was ruled out previously; `GLDMA2` and `GLDirectMem`
were not, and the per-image `advancedFeatures.ini` turns all three on. Disabling all three
together (r04) is accepted by the emulator and changes nothing:

```
INFO | Feature 'GLDMA' (51) is overridden to 'disabled'
INFO | Feature 'GLDMA2' (52) is overridden to 'disabled'
INFO | Feature 'GLDirectMem' (53) is overridden to 'disabled'
... 57 surfaceflinger aborts, device never boots
```

**Host composition.** `-feature -HostComposition` (r07) was the best remaining guess at what
forces the readback. It did not help; it made things worse, wedging adb entirely at 208 s so the
crash buffer could not even be read. Recorded as inconclusive rather than ruled out, because no
evidence came back from it.

**Guest feature negotiation differing from API 36.** It does not. The image-level
`advancedFeatures.ini` for `android-37.0` is byte-identical to `android-36`'s except for one
unrelated line:

```
$ diff android-36/google_apis/x86_64/advancedFeatures.ini android-37.0/google_apis/x86_64/advancedFeatures.ini
+QemuCameraSensorOrientation = on
```

`GLDMA`, `GLDMA2`, `GLDirectMem`, `GrallocSync`, `HostComposition` and `YUVCache` are on in
both. API 36 boots and passes. So nothing about the host/guest feature handshake changed — the
regression is in the guest's Gralloc5 mapper or in what API 37's `RegionSamplingThread` asks of
it, not in what the emulator advertises.

**Guest memory.** Ruled out previously and not revisited; every run above used
`hw.ramSize=2560`, the same value the E2E matrix pins, and none of them OOMed.

**A host-side crash.** Not this bug, and worth stating because the other emulator failure on this
workstation *is* host-side. Every run above left `coredumpctl` empty and produced zero
`avc: denied` lines, and the qemu process was still alive at the end of the ones that never
booted (`emulator_alive=yes`). The host emulator is fine; the guest is not.

## Can the suite run on it?

**Almost.** `tools/local-emulator/run-e2e.sh 37` now runs the whole suite locally and reports
**49 tests, 2 failures, 0 errors, 2 skipped** — reproduced twice, with the same two tests failing
both times. That is 47 of 49 against the Pixel's 49 of 49, and it costs two deviations from how
every other level is run. Both are worth understanding before trusting the leg.

### Booting is not the same as being usable

Changing the renderer gets the device to `sys.boot_completed=1`, and that is all it gets you. The
aborts do not stop, and each one is a framework restart. A five-minute test run does not survive
that. What it looks like from Gradle:

```
Shell command failed (1): rm -rf "/sdcard/Android/media/org.libremediaconverter/..."
    rm: ...: Transport endpoint is not connected
Starting 0 tests on lmc_e2e_api37(AVD) - 17
Shell command failed (20): am get-current-user
    cmd: Can't find service: activity
Device emulator-5572 failed to uninstall test APK org.libremediaconverter.
    [cmd: Can't find service: package]
Test run failed to complete. No test results.
    onError: commandError=false message=INSTRUMENTATION_ABORTED: System has crashed.
```

Measured idle rate on `android-37.0` under `swangle_indirect`: **10 aborts in 150 s, then 11 more
in the next 150 s**. Steady, not a start-up transient.

### The fix is to remove the region-sampling listener, not to survive it

`RegionSamplingThread` exists only because SystemUI registers a nav-bar luma-sampling listener.
Take SystemUI away and the thread is never started, so the mapper's bad path is never called:

```
$ adb shell pm disable-user --user 0 com.android.systemui
Package com.android.systemui new state: disabled-user

=== aborts at start of measurement: 36
=== idle 180s with SystemUI disabled ===
=== aborts after: 36   NEW IN WINDOW: 0
--- services still up? ---
  activity   Service activity: found
  package    Service package: found
  window     Service window: found
```

**Zero in 180 s, against 10–11 per 150 s.** That is the confirmation that region sampling is the
sole trigger, and it is worth recording even by someone who never wants the workaround.

One ordering detail cost a whole run and is now encoded in `disable_region_sampling`: by the time
`sys.boot_completed` flips, SystemUI has **already registered**, and `pm disable-user` does not
retract an existing registration — it only stops the package being started again. Disabling it
and proceeding straight to the tests fails exactly as before. The harness therefore does
`stop; start` afterwards, so the framework that comes back never starts SystemUI at all.

### The two deviations, stated plainly

1. **The renderer is ANGLE, not the host GPU.** Shared with nothing else in the matrix — API
   33–36 run `-gpu host` locally, and CI runs `swiftshader_indirect`.
2. **SystemUI is disabled.** The API 37 leg does not run the same device configuration as any
   other leg or as the Pixel. It is defensible here only because nothing in this suite touches
   system UI — these are Media3, FFmpeg and WorkManager tests — and because the alternative is no
   local API 37 coverage at all. **Anything that ever does depend on system UI must not trust
   this leg.**

### The two remaining failures are the same bug, one layer down

```
org.libremediaconverter.convert.Media3EngineTest > runsFromAThreadWithNoLooper          FAILED
org.libremediaconverter.convert.Media3EngineTest > transcodesH264ToH265AndReportsProgress FAILED

androidx.media3.transformer.ExportException: Codec exception:
    CodecInfo{type=VideoDecoder, ..., mime=video/avc, name=c2.goldfish.h264.decoder}
  at androidx.media3.transformer.DefaultCodec.maybeDequeueOutputBuffer(DefaultCodec.java:398)
Caused by: android.media.MediaCodec$CodecException:
  at android.media.MediaCodec.native_dequeueOutputBuffer(Native Method)
```

Three measurements say this is the emulator image and not this app, and not the software
renderer:

- **Control at API 35 under the identical renderer.** `GPU_MODE=swangle_indirect
  tools/local-emulator/run-e2e.sh 35` → **49 / 0 / 0 / 2**, green. `c2.goldfish.h264.decoder` is
  perfectly happy under ANGLE one API level down, so the renderer is not what breaks it.
- **Real API 37 hardware passes**, see below. There is no `c2.goldfish.*` codec on a Pixel.
- The failing call is `dequeueOutputBuffer` on the *goldfish* decoder — the emulator's own codec,
  which like `RegionSamplingThread` gets its frames out of a host-side colour buffer. Same
  readback machinery, one layer down. This is inference rather than a measurement, and is flagged
  as such; what is measured is the first two bullets.

**Do not try `-feature -HardwareDecoder`.** It is the obvious next idea and it is much worse:
forcing the guest onto software decoders took the run from 2 failures to **46**, across
`RemuxTest`, `ForcedFailureTest`, `HardwareFallbackTest` and `UnopenableUriTest` as well. The
suite depends on those decoders existing.

### So should CI take API 37?

**No, and the matrix should still stop at 36.** Three reasons, in order of weight:

1. CI runs `swiftshader_indirect` on a GPU-less runner. The aborts happen under ANGLE too — they
   are merely sparser — so nothing here says a runner would be stable.
2. The working configuration needs SystemUI disabled and a framework restart mid-job. That is a
   lot of bespoke device surgery to put behind a merge gate, and it silently weakens what the leg
   proves.
3. Even at its best it is 47 of 49, so the leg would be permanently red or permanently
   allow-listed. Neither is a gate worth having.

What has changed is the *local* story: API 37 is no longer a level nobody can look at. A
regression that shows up at 37 and not at 36 can now be reproduced on this workstation in about
four minutes, which is what the missing matrix row was really costing.

## Verified on real API 37 hardware

Unchanged and still true. On 2026-08-21 the whole instrumented suite ran green on a physical
device:

```
Device:  Pixel 10 Pro XL (mustang), arm64-v8a
Build:   google/mustang/mustang:17/CP2A.260805.005/15828068:user/release-keys
API:     37 (Android 17, codename REL -- a release build, not a preview)

./gradlew :app:connectedDebugAndroidTest -PabiFilters=arm64-v8a
  40 tests, 0 failures, 0 errors, 2 skipped        BUILD SUCCESSFUL
```

**That "40" is not a baseline to compare against today, and it is not a contradiction of the 49
in [`docs/local-emulator.md`](local-emulator.md).** It was accurate for the tree it ran on: at
`edd6385`, the commit that recorded it, `app/src/androidTest` contained exactly 40 `@Test`
methods. Nine have been added since and the suite is now 49. If you re-run on the Pixel, expect
49 / 0 / 0 / 2, and if you get 40, you are on an old checkout.

The two skips are `RealMediaBenchmark.hardwareVersusSoftwareOnRealVideo` and
`av1InputRoutesAccordingToDeviceDecodeSupport`, which `assumeTrue` their sample files are present
and skip when they are not. That is by design and unrelated to API level.

One harmless warning appears during the run and can be ignored:
`No UID for androidx.test.services in user 0`, from an `appops` call the test services package
makes before it is fully registered.

## Reproducing it

Both halves, so the renderer claim can be checked rather than taken on trust:

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

sdkmanager --install "system-images;android-37.0;google_apis;x86_64"
echo no | avdmanager create avd -n api37_repro \
    -k "system-images;android-37.0;google_apis;x86_64" -d pixel_6 --force

# never boots -- surfaceflinger aborts every ~6 s, forever
emulator -avd api37_repro -no-window -gpu host \
    -noaudio -no-boot-anim -camera-back none -no-snapshot &

# boots in ~85 s, having aborted once or twice on the way
emulator -avd api37_repro -no-window -gpu swangle_indirect \
    -noaudio -no-boot-anim -camera-back none -no-snapshot &
```

Count the aborts either way:

```bash
adb -s emulator-5554 logcat -d -b crash | grep -c hasReadColorBufferDma
```

Under `-gpu host`, `sys.boot_completed` never reaches `1`, `pgrep -f system_server` stays empty,
and `keystore2`'s watchdog logs `await_boot_completed ... Overdue` indefinitely.

`tools/local-emulator/run-e2e.sh 37` does all of this, with the working renderer picked
automatically — see `gpu_for_api` in that file.

## Filing this upstream

Not yet filed. The report is stronger than it was, because the renderer dependency narrows it:

1. Go to <https://issuetracker.google.com/>, **Report an issue**, and pick the Android emulator
   component (search the component picker for "Emulator"; Android Studio's **Help → Submit
   Feedback** opens the same tracker with it preselected).
2. Title it for the mechanism: `surfaceflinger aborts in GoldfishMapper::readFromHost
   (hasReadColorBufferDma) on android-37.0 and android-37.1 x86_64 -- fatal under -gpu host,
   intermittent under ANGLE`.
3. Paste the assertion and backtrace, the environment block, and the seven-row matrix. The
   matrix is the valuable part: it shows the abort is not renderer-specific but its *frequency*
   is, which points at the readback path rather than at any one GL implementation.
4. State that it reproduces on two independent system images (`37.0` rev 6 and `37.1` rev 8) and
   on two unrelated hosts, and that `-feature -GLDMA,-GLDMA2,-GLDirectMem` does not suppress it.
5. Attach:
   - the guest tombstone, via `adb pull /data/tombstones` (or the `pbtombstone` output the crash
     log names)
   - `adb logcat -d -b crash > crash.txt`
   - the emulator's own stdout log (`-verbose -debug all`, redirected)
   - the AVD's `config.ini`
   - a link to a failing CI job, which shows it on hardware you do not control:
     <https://github.com/JMR-dev/LibreMediaConverter/actions/runs/32545625459/job/96963461184>

Record the issue number here once filed.

## When to revisit

The old trigger list named "a new `android-37.0` revision", which is why nothing ever fired even
though a new API level shipped. Watch for these instead:

- **any new API 37.x system image**, not just a new revision of `37.0` — `37.1` rev 8 and
  `37.2-beta*` already exist, and more will. Test with `-gpu host`: if it boots, the guest mapper
  is fixed.
- **an ATD image for API 37.** Still none as of 2026-08-22. ATD images ship without SystemUI,
  which is what drives `RegionSamplingThread`, so one would very likely sidestep the bug
  entirely. Try it before anything else here.
- **the upstream issue being marked fixed.**

## Correction owed to `CLAUDE.md`

`CLAUDE.md` currently says:

> - **The API 37 image is broken.** `android-37.0` crash-loops surfaceflinger inside its own
>   gralloc mapper, so every test fails there regardless of this app.
>   `docs/api-37-emulator-crash.md` records the evidence and the ruled-out fixes; CI's matrix
>   therefore stops at API 36 even though targetSdk is 37.

The first sentence is right, and now under-specified in one direction and over-specified in the
other: it is not only `android-37.0` (it is `37.1` too), and it does not crash-loop under every
renderer. Proposed replacement, offered for review rather than applied here — `CLAUDE.md` is left
alone deliberately, because several branches touch it:

> - **The API 37 images crash-loop surfaceflinger under the host GL renderer.** Both
>   `android-37.0` and `android-37.1` abort inside their own gralloc mapper
>   (`RegionSamplingThread` → `GoldfishMapper::readFromHost`), and when surfaceflinger dies init
>   SIGKILLs zygote, so the framework restarts under the test run. Under `-gpu host` it never
>   boots at all; under `-gpu swangle_indirect` it boots and the aborts merely become
>   intermittent. `docs/api-37-emulator-crash.md` has the seven-run matrix and the ruled-out
>   list, and `tools/local-emulator/run-e2e.sh` picks the working renderer per API level.
>   CI's matrix stops at 36 because CI runs `swiftshader_indirect` on a GPU-less runner and the
>   aborts continue there too. **API 37 still needs a manual check on the Pixel 10 Pro XL before
>   each release.**
