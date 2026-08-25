# API 37 on the emulator: a guest gralloc bug that only the host GL renderer triggers

**Status:** the bug is real and still open upstream, but the previous diagnosis in this file was
wrong about its most important detail. **The renderer decides whether API 37 boots**, and once it
boots, disabling SystemUI collapses the crash rate far enough to run a suite —
`tools/local-emulator/run-e2e.sh 37` gets through the whole instrumented suite and comes back with
**2 failures, 0 errors and the two by-design skips** (measured 49 / 2 / 0 / 2 at `22c7914`, where
the suite was 49 tests — [Reading these totals](#reading-these-totals) before comparing any total
with another). The crashes do not stop outright, and the two failures are real; both are quantified
below. CI now takes API 37 as two jobs — a gating leg and an advisory one for those two
failures — see [So should CI take API 37?](#so-should-ci-take-api-37).
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

One caveat about how independent those rows are, because the table flatters itself. `-gpu
angle_indirect` (r05) and `-gpu swangle_indirect` (r03) both logged `gles_mode_selected:swangle`
and both reported the same adapter, differing only in the Vulkan backend beneath
(`vulkan_mode_selected:lavapipe` against `swiftshader`). So they are closer to one GLES path
reached two ways than to two renderers agreeing — note that at API 33–36
[`docs/local-emulator.md`](local-emulator.md) records `angle_indirect` resolving to ANGLE on
*llvmpipe*, a genuinely different adapter, which it did not do here. What is 7-for-7 is the
host-GLES-versus-not split, not "two independent renderers both work".

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

- **The capability is negotiated regardless of renderer.** The evidence is the aborts
  themselves: the assertion that fires is `!hasReadColorBufferDma`, and it fires under ANGLE
  (r03/r05/r06) as well as under the host translator — just far less often. That is a direct
  observation of the guest having negotiated DMA readback under both, and it stands alone.
  (Supporting only, and weaker than it first looks: `ANDROID_EMU_read_color_buffer_dma` appears
  in exactly one file in the SDK, `emulator/lib64/libgfxstream_backend.so`, which every `-gpu`
  mode goes through. A string search establishes where the extension is implemented, not that
  it is negotiated on every path.)
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

**Note the `ps16k` in the second one — it is not optional, and it is why the 37.1 result is
interpretable.** From API 37.1 onward Google ships *only* 16 KB-page x86_64 images; there is no
plain `google_apis` variant to pick. `sdkmanager --list` for 37.1 and 37.2-beta* offers nothing
but `google_apis_ps16k` and `google_apis_playstore_ps16k`. That makes page-size alignment a
prerequisite rather than a detail: a `.so` that is not 16 KB aligned will not load on such a
guest, and the resulting failure looks like an app bug. Checked before the first `ps16k` boot,
using the same test `build.yml` applies to release APKs — all 20 libraries in the committed
`bin/ffmpeg-kit-next-8.1.1.aar`, both ABIs, report `0x4000`:

```
$ for f in jni/*/*.so; do readelf -lW "$f" | awk '$1=="LOAD"{print $NF}' | sort -u; done
0x4000   (x20: libavcodec, libavdevice, libavfilter, libavformat, libavutil,
          libc++_shared, libffmpegkit, libffmpegkit_abidetect, libswresample, libswscale
          -- arm64-v8a and x86_64)
```

So when `android-37.1` reproduced the abort, that was the gralloc bug and not a page-size
mismatch. `image_pkg_for_api` in `tools/local-emulator/run-e2e.sh` encodes the `ps16k` tag for
37.1; if this ever fails after an FFmpeg rebuild, re-run the alignment check first.

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

**Almost, and less so than it was.** `tools/local-emulator/run-e2e.sh 37` runs the whole suite
locally. Measured at `22c7914`: **49 tests, 2 failures, 0 errors, 2 skipped** — 45 passed, the two
`Media3EngineTest` failures dissected below, and the two `assumeTrue` skips every level has. It
costs two deviations from how every other level is run, and both are worth understanding before
trusting the leg.

**That was the high-water mark.** On 2026-08-24 a test that touches system UI joined the suite,
and the level stopped *finishing* rather than merely failing two —
[see below](#something-does-depend-on-system-ui-now-and-it-is-excluded-rather-than-trusted).
Two `Media3EngineTest` failures is what **CI's gating leg** expects, because it filters on
`notAnnotation`; a local `run-e2e.sh 37` does not filter and sees more.

Two things about that total before it is compared with anything. It is the size of the suite on
the checkout that ran, not a property of API 37 — `app/src/androidTest` held 49 `@Test` methods at
`22c7914`, and a newer checkout reports its own count; see
[Reading these totals](#reading-these-totals). And **the Pixel has never run 49**: its green run
was 40 / 0 / 0 / 2 at `edd6385`, the same suite nine tests earlier. What compares across the two
is two failures against none, and the same two skips — not the totals.

The same numbers and the same two test names came back twice, which is real corroboration — but
by two different routes, and only one of them is the harness. The first was driven by hand
(`pm disable-user`, then several minutes of incidental framework restarts, then `e2e-run.sh`
directly); the second went through `disable_region_sampling`'s `stop; start`. **The harness path
itself has one green measurement.** What would make this routine is a second consecutive
`run-e2e.sh 37` whose only failures are the same two.

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

**Zero in 180 s, against 10–11 per 150 s.** That is the strongest evidence that region sampling
is the dominant trigger, and it is worth recording even by someone who never wants the workaround.
It does not establish it as the *only* trigger: the paragraph below has an abort surviving the
disable, and nothing measured here says whether that residue is a second caller of the readback
path or a disable that did not fully take.

Do not read that as "the crashes stop", though, because the harness path does not reproduce a
clean zero. Its own post-disable check on the run recorded below printed

```
  quiet check: 1 new surfaceflinger aborts in 45 s (want 0)
  surfaceflinger hasReadColorBufferDma aborts: 4     (whole run)
```

So what is reliably achieved is a **rate collapse** — from roughly one abort every fourteen
seconds to one every forty-five — which a 47-second Gradle run survives and a five-minute one
might not. The 180-second zero above is one measurement on a device that had been up for twelve
minutes and had already cycled its framework several times. The harness prints the quiet-check
delta on every run precisely so this is visible rather than assumed.

One ordering detail cost a whole run and is now encoded in `disable_region_sampling`: by the time
`sys.boot_completed` flips, SystemUI has **already registered**, and `pm disable-user` does not
retract an existing registration — it only stops the package being started again. Disabling it
and proceeding straight to the tests fails exactly as before. The harness therefore does
`stop; start` afterwards, so the framework that comes back never starts SystemUI at all.

### The two deviations, stated plainly

1. **The renderer is ANGLE, not the host GPU.** Shared with nothing else in the matrix — API
   33–36 run `-gpu host` locally, and CI runs `swiftshader_indirect`.
2. **SystemUI is disabled.** The API 37 leg does not run the same device configuration as any
   other leg or as the Pixel. It was defensible here because nothing in this suite touched
   system UI — Media3, FFmpeg and WorkManager tests — and because the alternative is no local
   API 37 coverage at all. **Anything that ever does depend on system UI must not trust this
   leg.** Something now does; see the section below.

### Something does depend on system UI now, and it is excluded rather than trusted

Added 2026-08-24, and it is the first entry on this page that is not a codec.

`SafPickerRoundTripTest` drives the real system file picker and rotates the display. Both reach
the gralloc mapper — DocumentsUI is another app's windows, and a rotation rebuilds every surface
on screen — and **disabling SystemUI does not help**, because it removes the *idle* trigger
(RegionSamplingThread's nav-bar luma sampling) and not this one. The two tests fail on
`android-37.0` under `swangle_indirect` with SystemUI disabled and verified quiet, and they were
measured **separately**, because inferring the second from the first would have been the same
mistake this page's opening correction is about:

| test | how it fails | `hasReadColorBufferDma` aborts in the window |
|---|---|---|
| `thePickedInputSurvivesARealRotation` | `INSTRUMENTATION_ABORTED: System has crashed.` — `Expected 59 tests, received 50`. The framework dies **during** it, so six later tests never run and the JUnit XML carries a failure with no text at all. | 5 |
| `pickingAFileThroughTheSystemPickerFillsInTheFileCard` | `androidx.test.uiautomator.StaleObjectException` at `UiObject2.click`, tapping the fixture root the previous line had just found. A restart rebuilt the window between the two. | 3 |

Both pass on API 33 and API 36 locally, whole suite, `59 / 0 / 0 / 2` on each — so this is the
image, on the same evidence pattern as the codec failures above.

The class therefore carries `@FailsOnEmulatorApi37` and runs on the advisory leg. **The rule the
deviation states is being applied, not broken:** the thing that depends on system UI does not
trust this leg.

Two consequences worth stating rather than discovering:

- **`run-e2e.sh 37` applies no annotation filter**, so a local API 37 run reports these on top of
  the two `Media3EngineTest` ones, and — new — **does not finish**. Its totals come back short,
  and which later tests ran is arbitrary. The summary row says so.
- **The advisory job is still named `E2E API 37 Media3 hardware transcode (advisory)`**, and it
  now carries two tests that are neither Media3 nor a transcode. Renaming a check is a branch-
  protection change and was deliberately not made in the same PR; the name is stale, the
  behaviour is correct.

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
renderer. A fourth bullet offers a mechanism, and is inference rather than measurement:

- **Control at API 35 under the identical renderer.** `GPU_MODE=swangle_indirect
  tools/local-emulator/run-e2e.sh 35` → **49 / 0 / 0 / 2** at `22c7914`, green.
  `c2.goldfish.h264.decoder` is perfectly happy under ANGLE one API level down, so the renderer is
  not what breaks it.
- **Real API 37 hardware passes**, see below. There is no `c2.goldfish.*` codec on a Pixel.
- **API 36 against API 37 on CI, back to back, everything else held.** Same two tests, same
  `-gpu swiftshader_indirect`, same SystemUI-disable path — `pm disable-user`, `stop`, wait for
  `system_server` to actually be gone, `start`, then verify against `pm list packages -d`. Both
  runs were narrowed to the two failing tests:

  ```
  -Pandroid.testInstrumentationRunnerArguments.class=\
    org.libremediaconverter.convert.Media3EngineTest#transcodesH264ToH265AndReportsProgress,\
    org.libremediaconverter.convert.Media3EngineTest#runsFromAThreadWithNoLooper
  ```

  and the filter is confirmed three independent ways: `tests="2"` in the XML, `Expected 2 tests`
  in the abort message, and `run started: 2 tests` in the guest logcat.

  | run | api | result XML |
  |---|---|---|
  | [32660148155](https://github.com/JMR-dev/LibreMediaConverter/actions/runs/32660148155) | 37.0 | `tests="2" failures="2" errors="0" skipped="0"` |
  | [32660152961](https://github.com/JMR-dev/LibreMediaConverter/actions/runs/32660152961) | 36 | `tests="2" failures="0" errors="0" skipped="0" time="4.603"` |

  API 37 fails with the signature above — `name=c2.goldfish.h264.decoder`,
  `MediaCodec$CodecException` at `dequeueOutputBuffer(MediaCodec.java:4274)`. API 36 passes both in
  4.603 s, and `c2.goldfish.h264.decoder` is in *its* logcat too (44 mentions), so the two runs are
  not being served by different decoder names. **What this falsifies is "the stripped
  configuration is what breaks these tests"** — a reading none of the other measurements
  addresses, because they all compare against a device that still had SystemUI. Here SystemUI is
  absent and the framework has been restarted on both sides, and the healthy image is green anyway.

  Two things it does **not** control, which is why it narrows the claim rather than closing it:

  - **The restarts were not performed under equal conditions.** API 36 did its `stop`/`start` with
    `dma_aborts=0`; API 37's did the same restart with two aborts already logged. "A framework
    restart performed while the abort loop is running" therefore remains uncontrolled.
  - **The images differ on the encoder side.** These tests transcode H.264 → H.265. The API 37
    logcat carries `c2.goldfish.hevc.decoder` (16 mentions in the control run) where API 36 carries
    `c2.android.hevc.encoder` (32). The pipeline is not identical end to end, which is a second
    reason "the image ships a broken h264 decoder" is the wrong *shape* of claim: what is measured
    is that these two tests fail on the API 37 image, pass at API 36 under the same renderer *and*
    the same disable path, and pass at 33–36 without needing that path at all — because nothing
    below 37 has the bug it works around.
- The failing call is `dequeueOutputBuffer` on the *goldfish* decoder — the emulator's own codec,
  which like `RegionSamplingThread` gets its frames out of a host-side colour buffer. Same
  readback machinery, one layer down. This is inference rather than a measurement, and is flagged
  as such; what is measured is the first three bullets.

**Do not try `-feature -HardwareDecoder`.** It is the obvious next idea and it is much worse:
forcing the guest onto software decoders took the run from 2 failures to **46**, across
`RemuxTest`, `ForcedFailureTest`, `HardwareFallbackTest` and `UnopenableUriTest` as well. The
suite depends on those decoders existing.

### The intact-SystemUI counterfactual cannot be measured on CI

The control the block above still lacks is the obvious one: run those same two tests at API 37
with SystemUI **left running**. Passing would put the failure on the disable rather than on the
image; failing on the decoder would make the decoder attribution direct instead of inferred.

**Seven dispatches of `api37-debug.yml`, zero verdicts.** Not bad luck — a mechanism, which is why
this is written down rather than left as a gap for the next person to spend seven runs on:

| arm | run | result XML | what actually happened |
|---|---|---|---|
| E1 | [32660528355](https://github.com/JMR-dev/LibreMediaConverter/actions/runs/32660528355) | `tests="1" failures="1"`, `<failure>` body empty | `Expected 2 tests, received 0. INSTRUMENTATION_ABORTED: System has crashed.` |
| E2 | [32660533845](https://github.com/JMR-dev/LibreMediaConverter/actions/runs/32660533845) | `tests="0"` | never installed: `Failed to commit install session ... Failure calling service package: Broken pipe (32)` |
| E3 | [32660539259](https://github.com/JMR-dev/LibreMediaConverter/actions/runs/32660539259) | `tests="0"` | `Test run failed to complete. No test results.` |
| E4 | [32661117237](https://github.com/JMR-dev/LibreMediaConverter/actions/runs/32661117237) | `tests="2" failures="2"` | both failed in `@Before`, never reached MediaCodec |
| E5 | [32661121972](https://github.com/JMR-dev/LibreMediaConverter/actions/runs/32661121972) | `tests="2" failures="2"` | same |
| S1 | [32661127224](https://github.com/JMR-dev/LibreMediaConverter/actions/runs/32661127224) | `tests="1" failures="1"` | same, single-test arm |
| S2 | [32661132024](https://github.com/JMR-dev/LibreMediaConverter/actions/runs/32661132024) | `tests="1" failures="1"` | same |

While the framework is crash-looping, the guest cannot reliably create per-user private
directories. An app installed during the loop has no cache directory — and `Media3EngineTest`
copies its H.264 fixture into `context.cacheDir` in `@Before`, so it dies there, **before any
MediaCodec exists**:

```
W/ContextImpl( 8216): Failed to ensure /data/user/0/org.libremediaconverter/cache
I/TestRunner( 8216): run started: 1 tests
E/TestRunner( 8216): failed: transcodesH264ToH265AndReportsProgress(...)
E/TestRunner( 8216): java.io.FileNotFoundException:
  /data/user/0/org.libremediaconverter/cache/sample_h264.mp4: open failed: ENOENT
  at org.libremediaconverter.convert.Media3EngineTest.setUp(Media3EngineTest.kt:47)
```

Not app-specific: `com.google.android.googlesdksetup` and `com.google.android.apps.nexuslauncher`
hit the same `Failed to ensure /data/user/0/<pkg>/cache` in the same logcats.

**The result XML masks this, and reading only the report gets you the wrong bug.** What E4, E5, S1
and S2 report is

```
<failure>kotlin.UninitializedPropertyAccessException: lateinit property output has not been initialized
at org.libremediaconverter.convert.Media3EngineTest.tearDown(Media3EngineTest.kt:56)
```

— `tearDown` failing because `setUp` threw before it assigned `output`. That looks like a
teardown defect in this repository and is not one; the cause is only in the guest logcat.

So the obstacle is structural: install, data-directory creation and instrumentation start-up do
not fit between framework kills, and four of the seven runs show the directory creation itself is
broken during the loop. More dispatches of this shape would repeat these outcomes. The
counterfactual is still open on the **Pixel 10 Pro XL**, the one API 37 device here that is not an
emulator — but a Pixel has no `c2.goldfish.*` codec at all, so it answers "does the app work at
API 37", not "is that codec broken".

#### Abort cadence, corrected

`.github/workflows/api37-debug.yml` carried "roughly every 20 s" for the kill cycle in its own
comments. That number was the watchdog's **sampling** interval, not the cadence, and the two got
conflated. Measured across the seven runs above, gaps between successive `hasReadColorBufferDma`
aborts run **20 s to 90 s, median 60–70 s — three to five aborts in a four-minute window**.
Slower than assumed, and still not slow enough: install, data-directory creation and
instrumentation start-up do not fit inside one gap.

`sys.boot_completed` held at `1` throughout every one of those test windows. The device reports
itself booted while zygote is being killed under it, which is why no boot-state check catches
this and why `stop`/`start` waits must poll `pidof system_server` and `service check` instead
(see `disable_region_sampling` in `tools/local-emulator/run-e2e.sh`).

### So should CI take API 37?

**Yes, as two jobs: a gating `E2E API 37` and an advisory leg carrying the two tests that do not
pass.** That reverses the answer this section gave, and the reversal is measured rather than
argued — two of its three reasons were claims *about CI*, and CI had never been measured. The
instrument that measured it is [`.github/workflows/api37-debug.yml`](../.github/workflows/api37-debug.yml),
dispatch-only, a copy of the E2E job with the matrix replaced by inputs.

Every run below is `ubuntu-latest`, KVM on, `pixel_6`, x86_64, disk 8G, RAM 2560M, emulator
`37.1.11.0` build 15917651 — the same emulator build the local investigation used. Every **API
37** row is `system-images;android-37.0;google_apis;x86_64`; c2 is the API 36 control and runs
that level's own image, which is the whole point of it.

| # | run | api | `-gpu` | SystemUI | suite | verdict |
|---|---|---|---|---|---|---|
| c1 | [32644947334](https://github.com/JMR-dev/LibreMediaConverter/actions/runs/32644947334) | 37.0 | swiftshader_indirect | running | `Starting 0 tests` | FAIL |
| c2 | [32644965828](https://github.com/JMR-dev/LibreMediaConverter/actions/runs/32644965828) | **36** | swiftshader_indirect | running | 57 tests, BUILD SUCCESSFUL | green control |
| c3 | [32644970240](https://github.com/JMR-dev/LibreMediaConverter/actions/runs/32644970240) | 37.0 | swangle_indirect | running | `Starting 0 tests` | FAIL |
| c5 | [32645543238](https://github.com/JMR-dev/LibreMediaConverter/actions/runs/32645543238) | 37.0 | swangle_indirect | disabled | 57 / 2 / 0 / 2 | suite ran |
| c6 | [32646029143](https://github.com/JMR-dev/LibreMediaConverter/actions/runs/32646029143) | 37.0 | swiftshader_indirect | one-shot disable, **did not hold** | `Starting 0 tests` | FAIL |
| c8 | [32646611485](https://github.com/JMR-dev/LibreMediaConverter/actions/runs/32646611485) | 37.0 | swiftshader_indirect | disabled, verified | 57 / 2 / 0 / 2 | suite ran |
| c9 | [32646615706](https://github.com/JMR-dev/LibreMediaConverter/actions/runs/32646615706) | 37.0 | swiftshader_indirect | disabled, verified | 57 / 2 / 0 / 2 | suite ran |
| c10 | [32646619472](https://github.com/JMR-dev/LibreMediaConverter/actions/runs/32646619472) | 37.0 | swiftshader_indirect | disabled, verified | 57 / 2 / 0 / 2 | suite ran |
| c11 | [32647138060](https://github.com/JMR-dev/LibreMediaConverter/actions/runs/32647138060) | 37.0 | swiftshader_indirect | disabled, verified | 57 / 2 / 0 / 2 | suite ran |

57 is that checkout's own `@Test` count at `acc71bc`, so those are whole-suite runs and not
truncated ones — see [Reading these totals](#reading-these-totals). Taking the three old reasons
in turn:

1. **"Nothing says a runner would be stable" — measured, and it is.** `-gpu swiftshader_indirect`
   on a GPU-less runner resolves to `gles_mode_selected:swiftshader`, a third renderer that
   locally never survives to say anything (Fedora's SELinux denies `execheap` to SwiftShader's
   Reactor JIT — see [`local-emulator.md`](local-emulator.md)). It boots `android-37.0` in about
   60 s. The local discriminator — fatal iff `gles_mode_selected:host` — holds, and a runner with
   no GPU can never select host, so CI was never in the fatal class. Switching CI's `-gpu` changes
   nothing either way: c1 and c3 both fail with SystemUI up, under swiftshader and swangle
   respectively, and c5 and c8–c11 show the suite running under either once SystemUI is gone.
2. **"Bespoke device surgery" — still true, and now a written caveat rather than a reason to skip
   the level.** It is one env flag, `E2E_DISABLE_SYSTEM_UI`, read by `.github/scripts/e2e-run.sh`
   and unset on every other leg. What it costs is stated where it can be read from the failing
   check: the API 37 row runs a device configuration no other leg and no Pixel run uses. What
   makes it dependable is verification, not repetition — c6 is the counter-case, a one-shot
   `pm disable-user` that reported `new state: disabled-user` and then started SystemUI eight more
   times. The verified form is 4/4; the unverified form was 3/4.
3. **"Permanently red or permanently allow-listed" — this was the real objection, and it is the
   one the split answers.** The two failures are marked `@FailsOnEmulatorApi37` in
   `app/src/androidTest`. The gating job runs `notAnnotation` on that marker and must be green;
   the advisory job runs `annotation` on the *same* marker, reports, and never blocks. One marker
   rather than two lists, so a test cannot silently end up in neither job — which would read as
   green.

The cost is about three minutes on the API 37 leg — the `stop`/`start` plus a 45 s quiet window,
and another round when the first does not verify. Measured wall clock for the whole job, boot
included: 6–7 minutes at API 37 against ~6 at API 36.

Two things this does **not** buy. The advisory job is expected red, so a *third* failure there is
the signal and the run's logcat is the only thing that distinguishes it — which is why that job
uploads diagnostics unconditionally. And a green `E2E API 37` still does not replace the release
check on the Pixel: the emulator leg runs without SystemUI, and the Pixel does not.

The *local* story changed at the same time and independently: API 37 is no longer a level nobody
can look at. A regression that shows up at 37 and not at 36 can be reproduced on this workstation
in about four minutes.

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

**That "40" is a measurement of the tree it ran on, not a baseline for today**, and it is not a
contradiction of the totals in [`docs/local-emulator.md`](local-emulator.md) either.

### Reading these totals

Every total in this file and in [`docs/local-emulator.md`](local-emulator.md) is the size of
`app/src/androidTest` on the checkout that produced it, and nothing else. The reported total has
equalled that checkout's `@Test` count everywhere it has been checked:

| checkout | `@Test` methods | total the run reported |
|---|---|---|
| `edd6385` | 40 | 40 — the Pixel run above |
| `22c7914` | 49 | 49 — the four local levels, and API 37 |
| `18c53a3` | 57 | not run |

So the number to expect is not written down here. It is derived from the checkout in front of
you, which is the only thing that cannot go stale:

```bash
grep -rho '@Test' app/src/androidTest | wc -l
```

**Before each release, run the suite on the Pixel 10 Pro XL and expect that many tests, 0
failures, 0 errors, 2 skipped.** The failure, error and skip counts are the invariant; the total
is not. A total that disagrees with your own checkout's count is the signal — an old checkout, a
stale build, or tests that never ran — and it is worth stopping on either way.

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
- **`E2E API 37 Media3 hardware transcode (advisory)` going green.** Nothing announces this: the
  job is `continue-on-error`, so it fixing itself looks exactly like a check nobody reads
  quietly ceasing to be red. It is listed here because that makes it the *least* likely of these
  triggers to be noticed, not the most. When it happens, delete `@FailsOnEmulatorApi37` from
  everything carrying it rather than deleting the job — the gating leg picks them back up on its
  own, and the advisory job then runs nothing and can go.

  **It is not two tests any more.** As of 2026-08-24 the marker is on `Media3EngineTest`'s two
  methods *and* on `SafPickerRoundTripTest` as a class, and the two groups fail for unrelated
  reasons — a codec and the gralloc mapper. They can go green independently, so check both before
  concluding the marker is done; and the job's name still says "Media3 hardware transcode", which
  half of what it runs is not.

## Correction owed to `CLAUDE.md`

`CLAUDE.md` currently says:

> - **The API 37 image is broken.** `android-37.0` crash-loops surfaceflinger inside its own
>   gralloc mapper, so every test fails there regardless of this app.
>   `docs/api-37-emulator-crash.md` records the evidence and the ruled-out fixes; CI's matrix
>   therefore stops at API 36 even though targetSdk is 37.

The first sentence is right, and now under-specified in one direction and over-specified in the
other: it is not only `android-37.0` (it is `37.1` too), and it does not crash-loop under every
renderer. **The last clause is now simply false: CI's matrix does not stop at API 36 any more.**
Proposed replacement, offered for review rather than applied here — `CLAUDE.md` is left alone
deliberately, because several branches touch it:

> - **The API 37 images crash-loop surfaceflinger under the host GL renderer.** Both
>   `android-37.0` and `android-37.1` abort inside their own gralloc mapper
>   (`RegionSamplingThread` → `GoldfishMapper::readFromHost`), and when surfaceflinger dies init
>   SIGKILLs zygote, so the framework restarts under the test run. Under `-gpu host` it never
>   boots at all; under `-gpu swangle_indirect` it boots and the aborts merely become
>   intermittent. `docs/api-37-emulator-crash.md` has the seven-run matrix and the ruled-out
>   list, and `tools/local-emulator/run-e2e.sh` picks the working renderer per API level.
>   CI takes API 37 as two jobs: a gating `E2E API 37` that disables SystemUI first, and an
>   advisory leg carrying the two `@FailsOnEmulatorApi37` tests. The gating leg therefore runs a
>   device configuration nothing else does. **API 37 still needs a manual check on the Pixel 10
>   Pro XL before each release** — it is the only API 37 run with SystemUI intact.
