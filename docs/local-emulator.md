# Emulators do run on this host: the segfault is SwiftShader's JIT against SELinux

**Status:** solved. Local instrumented runs work with `-gpu host`, and the whole suite is green
on API 33–36 — 0 failures, 0 errors and the two by-design skips on every level, measured as
49 / 0 / 0 / 2 at `22c7914`, where the suite was 49 tests. See [The sweep, run](#the-sweep-run),
and [Reading these totals](api-37-emulator-crash.md#reading-these-totals) before comparing any
total with another checkout's.
**Last verified:** 2026-08-22, emulator `37.1.11.0` (build 15917651), Fedora 44,
kernel `7.1.8-200.fc44`, `selinux-policy-44.6-1.fc44`

`CLAUDE.md` has said "Emulators segfault on this host — qemu dies on every AVD", and the
PR that introduced the E2E matrix called it "exit 139 across three AVDs and both GPU
backends, environmental". That is accurate about the symptom and wrong about the cause.
The crash is not environmental in the sense of "this machine is broken". It is one
specific renderer meeting one specific SELinux rule, and choosing a different renderer
avoids it completely.

## Summary

SwiftShader's Reactor JIT writes generated GLES shader code onto the **heap** and then
calls `mprotect` to make it executable. Fedora's SELinux policy denies that: the
`execheap` permission is not granted to `unconfined_t`, and the `selinuxuser_execheap`
boolean is off by default. The `mprotect` fails, the page stays writable-but-not-
executable, and the emulator dies with `SIGSEGV` the instant SwiftShader calls into the
routine it just generated.

The denial and the crash are the same event, one second apart, every time:

```
Aug 22 19:05:28 dunwall audit[655856]: AVC avc:  denied  { execheap } for  pid=655856
    comm="RenderThread" scontext=unconfined_u:unconfined_r:unconfined_t:s0-s0:c0.c1023
    tcontext=unconfined_u:unconfined_r:unconfined_t:s0-s0:c0.c1023 tclass=process permissive=0

Sat 2026-08-22 19:05:37 CDT 655856 1000 1000 SIGSEGV present
    /home/jasonross/Android/Sdk/emulator/qemu/linux-x86_64/qemu-system-x86_64-headless
```

Since 2026-08-20 the emulator is the *only* source of AVC denials on this machine —
18 of them, all `execheap`, all `comm="RenderThread"`.

## The backtrace

From `coredumpctl` core `189722` (`-avd mc_api34 -no-window -gpu off`), and identical
frame-for-frame in core `176218` (`-avd mc_test_api35 -gpu swiftshader_indirect`):

```
#0  0x000055fe64091070 in ?? ()
#1  0x00007f32a4cd58fc in ?? () from emulator/lib64/gles_swiftshader/libGLESv2.so
#2  0x00007f32a4cd4d4f in ?? () from emulator/lib64/gles_swiftshader/libGLESv2.so
#3  0x00007f32a4cd4b03 in ?? () from emulator/lib64/gles_swiftshader/libGLESv2.so
#4  0x00007f32a4cd4a55 in ?? () from emulator/lib64/gles_swiftshader/libGLESv2.so
#5  0x00007f32a4d5c1ab in ?? () from emulator/lib64/gles_swiftshader/libGLESv2.so
#6  0x00007f32c47b0c19 in start_thread () from /lib64/libc.so.6
#7  0x00007f32c48345cc in __clone3 () from /lib64/libc.so.6
```

Frame 0 has no symbol because it is not in any library — it is JIT output. The frames
below it are SwiftShader's own worker-thread pool (`libGLESv2.so` is stripped, so the
static functions do not resolve; the shipped `.so` exports 713 symbols and the last of
them ends at `0xb7f23`, well below these offsets).

Frame 0 is the interesting part. `rip` sits on the *first* instruction of a well-formed
function, and gdb disassembles it cleanly:

```
rip  0x55fe64091070
rsp  0x7f31cec4fe38          <- return address; the call had just landed

=> 0x55fe64091070:  push   %rbp
   0x55fe64091071:  push   %r15
   0x55fe64091073:  push   %r14
   0x55fe64091075:  push   %r13
   0x55fe64091077:  push   %r12
   0x55fe64091079:  push   %rbx
```

Readable, valid code, faulting on its own first byte. That only happens when the page is
not executable — and the core's program headers say exactly that. The `PT_LOAD` covering
`0x55fe64091070` is:

```
LOAD  0x00000000006a5000  0x000055fe5b0fb000  0x0000000000000000
      0x000000000d155000  0x000000000d155000   RW    0x1000
```

`RW`, with no `E`. Range `0x55fe5b0fb000`–`0x55fe68250000`, which contains the faulting
address. SwiftShader wrote the code, asked for `PROT_EXEC`, was refused, and jumped
there anyway. That is the whole bug.

## Which GPU modes crash, and which do not

All seven cells on the same AVD (`mc_api34`, `google_apis` x86_64, `pixel_6`),
headless, within six minutes of each other on 2026-08-22:

| `-gpu` | GLES implementation the emulator chose | Loads SwiftShader GLES | Result |
|---|---|---|---|
| `host` | Host — Intel Iris Xe (TGL GT2) | no | **booted, 20 s** |
| `angle_indirect` | ANGLE on llvmpipe | no | **booted, 26 s** |
| `swangle_indirect` | ANGLE on SwiftShader *Vulkan* | no | **booted, 25 s** |
| `auto` | SwiftShader GLES | yes | SIGSEGV, exit 139 |
| `off` | SwiftShader GLES (via fallback) | yes | SIGSEGV, exit 139 |
| `guest` | SwiftShader GLES (via fallback) | yes | SIGSEGV, exit 139 |
| `swiftshader_indirect` | SwiftShader GLES | yes | SIGSEGV, exit 139 |

The predictor is exact and mechanical, 7 for 7: **a run segfaults if and only if it
`dlopen`s `lib64/gles_swiftshader/libGLESv2.so`.** Grep any emulator log for
`Calling dlopen on .../gles_swiftshader/libGLESv2.so` and you know the outcome before it
happens.

Three details in that table are worth spelling out, because each of them is a way to
walk into the crash while believing you have avoided it.

**`auto` is not safe, and `auto` is the default.** With `-no-window`, `auto` does not
pick the host GPU even though there is one. It resolves to lavapipe + SwiftShader GLES:

```
DEBUG | emuglConfig_init: gpu_mode_requested: auto, no_window: 1
INFO  | emuglConfig_init: vulkan_mode_selected:lavapipe gles_mode_selected:swangle
INFO  | Graphics Adapter Android Emulator OpenGL ES Translator (Google SwiftShader)
INFO  | Graphics API Version OpenGL ES 3.0 (OpenGL ES 3.0 SwiftShader 4.0.0.1)
```

So a headless launch with no `-gpu` flag at all crashes. That is why the failure looked
universal: one of the historical cores (PID 243631) has the command line
`-avd mc_test_api35 -no-window -no-snapshot -no-boot-anim -no-audio` — no renderer
specified, therefore `auto`, therefore SwiftShader, therefore exit 139.

There is a caveat here that the table above cannot show, and it argues for refusing `auto`
rather than for trusting it. The `angle_indirect` run *also* logged
`gpu_mode_requested: auto` and *also* logged
`vulkan_mode_selected:lavapipe gles_mode_selected:swangle` — the same two lines as the
crashing `auto` run — and then resolved to ANGLE on llvmpipe and booted:

```
INFO | Graphics Adapter ... (ANGLE (Mesa, Vulkan 1.4.318 (llvmpipe (LLVM 21.0.0 256 bits)
     | (0x00000000)), llvmpipe-25.2.4))
```

Each mode was measured once, so what is established is the `dlopen`-to-outcome rule, not
that a given `-gpu` value always produces the same `dlopen`. On that evidence `auto` is a
mode that has been seen resolving two different ways, one of which is fatal — which is a
better reason to refuse it than a mode that simply always fails, because a renderer that
usually works is the kind that breaks a run on the day it matters. `host`,
`angle_indirect` and `swangle_indirect` name a renderer outright and leave nothing to
resolve.

**`off` and `guest` are not an escape hatch.** They ask for in-guest rendering, the
system image does not support it, and the emulator silently falls back:

```
WARNING | Your AVD has been configured with an in-guest renderer, but the system image
        | does not support guest rendering. Falling back to 'lavapipe' mode.
INFO    | Graphics Adapter Android Emulator OpenGL ES Translator (Google SwiftShader)
```

**"SwiftShader" is two different things, and only one of them crashes.**
`swangle_indirect` runs ANGLE's GLES on top of SwiftShader's *Vulkan* device and boots
fine:

```
INFO | emuglConfig_init: vulkan_mode_selected:swiftshader gles_mode_selected:swangle
INFO | Graphics Adapter ... (ANGLE (Google, Vulkan 1.3.0 (SwiftShader Device (Subzero)
     | (0x0000C0DE)), SwiftShader driver-5.0.0))
```

It is SwiftShader's own GLES translator — `libGLESv2.so`, reporting itself as
"SwiftShader 4.0.0.1" — whose JIT wants `execheap`. The Vulkan device does not. So
"avoid SwiftShader" is too blunt a rule; the rule is "avoid SwiftShader GLES".

By contrast, `-gpu host` finds the real hardware and never touches the JIT:

```
INFO | emuglConfig_init: vulkan_mode_selected:host gles_mode_selected:host
INFO | Found physical GPU 'Intel(R) Iris(R) Xe Graphics (TGL GT2)',
     | type: VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU, apiVersion: 1.4.354, driverVersion: 26.1.7
DEBUG| Renderer initialized successfully
INFO | Boot completed in 19941 ms
```

## The working configuration

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"

emulator -avd <name> -no-window -gpu host \
    -noaudio -no-boot-anim -camera-back none -no-snapshot
```

`tools/local-emulator/run-e2e.sh` does this, plus AVD creation, boot-wait, CI's
`disk-size`/`ram-size` pins, and device pinning; it then hands off to
`.github/scripts/e2e-run.sh` for the run itself. Use it rather than the raw command:

```bash
tools/local-emulator/run-e2e.sh          # API 33 34 35 36 37
tools/local-emulator/run-e2e.sh 35       # one level
tools/local-emulator/run-e2e.sh 37 37.1  # both API 37 images
GPU_MODE=swangle_indirect tools/local-emulator/run-e2e.sh 35
```

Levels are the labels above, not SDK ints: API 37's SDK directories are dotted
(`android-37.0`, `android-37.1`) and there is no `android-37`, so `37` is accepted as a
spelling of `37.0`. Setting `GPU_MODE` forces one renderer on every level, which is what
you want when measuring a mode; leaving it unset lets `gpu_for_api` pick, which is what
you want when running the suite — 33–36 need `host` and 37 must not have it.

**A bare `run-e2e.sh` exits 1, and that is the design.** API 37 is in the default list
deliberately — leaving it out is what left the level unlooked-at for as long as it was — and
it is permanently two failures short of green: `Media3EngineTest` cannot drive the emulator's
`c2.goldfish.h264.decoder` on those images, which
[`api-37-emulator-crash.md`](api-37-emulator-crash.md) pins on the image and not on this app
(API 35 under the same renderer is green). The summary row names the two expected failures so
that a third is visibly new, and the script repeats the point on the way out. Anything that
treats a non-zero exit as breakage — a wrapper, a hook, a habit — should name the levels it
wants: `run-e2e.sh 33 34 35 36` is the sweep that can be green.

`swangle_indirect` is the fallback worth knowing about. It is entirely software, so it
does not depend on reaching the session's GPU — useful over plain SSH, where `-gpu host`
has not been tested and may not find a device. It is also the closest local analogue to
what CI actually runs.

### Pinning the device, and one AGP bug in the way

CI has exactly one device attached; this workstation usually has a physical Pixel on USB
as well, so an unpinned `connectedDebugAndroidTest` installs and runs the suite on the
phone. `--serial` looks like the right answer — AGP's own help says it "will take
precedence over the serials specified in the `ANDROID_SERIAL` environment variable" and
that the task "will fail if it cannot connect to the device", which is exactly the
loud-failure behaviour wanted. **It does not work on AGP 9.3.1:**

```
java.lang.UnsupportedOperationException
    at com.google.common.collect.ImmutableCollection.remove(ImmutableCollection.java:280)
    at DeviceProviderInstrumentTestTask.getFilteredDevices(DeviceProviderInstrumentTestTask.java:519)
    at DeviceProviderInstrumentTestTask.runTestsWithTestRunner(...:451)
```

`getFilteredDevices` calls `remove()` on an `ImmutableList`, so the task dies before it
reaches any device.

Scope of that claim, since it is narrower than "AGP cannot pin devices": this was measured
with **two** devices attached — the emulator and the Pixel — and one of them filtered out.
`remove()` presumably only gets called when there is something to remove, so a single
matching device very likely never reaches it. It was not tested that way. What is
established is that `--serial` cannot be used on this workstation, which is the only place
it was needed.

`ANDROID_SERIAL` is a different code path and is fine: `ConnectedDeviceProvider` splits the
variable and keeps devices with `Set.contains(device.getSerialNumber())`, building a new
list rather than mutating one. So the harness pins with the environment variable and then
re-checks, from the report, which device actually ran — belt and braces, because the
variable gives no up-front guarantee the way `--serial` was supposed to. Retry `--serial`
after an AGP upgrade.

## The sweep, run

`tools/local-emulator/run-e2e.sh`, one invocation per level so each got a freshly created
AVD, `-gpu host` throughout, 2026-08-22 19:42–19:56, on `22c7914`. All four levels agree exactly,
and 49 is that checkout's whole suite — every `@Test` in `app/src/androidTest`, two of which skip
by design everywhere:

| API | Android | AVD | Boot | `connectedDebugAndroidTest` | Tests | Failures | Errors | Skipped |
|---|---|---|---|---|---|---|---|---|
| 33 | 13 | `lmc_e2e_api33` | 50 s | 1 m 54 s | 49 | 0 | 0 | 2 |
| 34 | 14 | `lmc_e2e_api34` | 55 s | 42 s | 49 | 0 | 0 | 2 |
| 35 | 15 | `lmc_e2e_api35` | 40 s | 3 m 46 s | 49 | 0 | 0 | 2 |
| 36 | 16 | `lmc_e2e_api36` | 90 s | 2 m 18 s | 49 | 0 | 0 | 2 |

The physical Pixel has never reported 49, and an earlier version of this paragraph said the
sweep matched it exactly. Its green API 37 run was 40 / 0 / 0 / 2, at `edd6385` — the same suite
nine tests earlier. What matches is 0 failures, 0 errors and the same two skips; totals only ever
match between runs of one checkout, which
[`api-37-emulator-crash.md`](api-37-emulator-crash.md#reading-these-totals) sets out.

Thirteen and a half minutes for the four levels, AVD creation and cold boots included;
fifteen with the pre-warm build in front of them. Nothing needed a retry, and no level
produced a `diagnostics-api*.txt` — `e2e-run.sh` writes that only on the failure path, so
their absence corroborates the four green rows.

Two things in the table are not per-level costs and should not be read as one. The gradle
column swings from 42 s to 3 m 46 s because three other agents were building on this
machine throughout; the work is the same 49 tests at every level. And these boots are
slower than the 20 s the mode matrix above records for `-gpu host`: that number came from
reusing one existing AVD seven times inside six minutes, where each row here creates an
AVD and boots it for the first time, with an 8 G userdata partition to initialise.

The boot column is the harness's own figure — it polls `sys.boot_completed` every five
seconds, so it is coarse, and it is not the emulator's `Boot completed in NNNNN ms` line.
The two measure different events and disagree in both directions: API 33 read 50 s against
the emulator's 39648 ms, API 35 read 40 s against its 48223 ms. Neither is wrong; the
property becomes readable over adb at a different moment from the one the emulator logs.

**The counts are from the XML, and only the XML.** `app/build/outputs/androidTest-results/TEST-*.xml`
has `tests="49" failures="0" errors="0" skipped="2"` on every level. The UTP console
counter disagrees, and it is the one that is wrong — it counts a skip twice, so it walks
off the end of its own denominator:

```
lmc_e2e_api35(AVD) - 15 Tests 48/49 completed. (2 skipped) (0 failed)
lmc_e2e_api35(AVD) - 15 Tests 50/49 completed. (2 skipped) (0 failed)
lmc_e2e_api35(AVD) - 15 Tests 51/49 completed. (2 skipped) (0 failed)
Finished 51 tests on lmc_e2e_api35(AVD) - 15
```

49 real tests, 51 on screen, on all four levels. `summarise_results` in the harness reads
the XML for this reason; quote it rather than the terminal.

The two skips are the same two every time, and both are meant to skip:
`RealMediaBenchmark.hardwareVersusSoftwareOnRealVideo` and
`RealMediaBenchmark.av1InputRoutesAccordingToDeviceDecodeSupport` are `assumeTrue`-guarded
on sample media that is deliberately not committed. Its third test,
`reportDeviceEncoderCapabilities`, has no such guard and runs. A level reporting 0 skipped
would mean someone had staged sample files, not that something improved.

### What the sweep adds, and what it does not

**The renderer rule held four more times.** No boot log contains the string
`gles_swiftshader`, all four selected `vulkan_mode_selected:host gles_mode_selected:host`
and found the Iris Xe, and the window covering the sweep has zero `avc: denied` lines and
zero qemu coredumps. That is confidence in a cell the mode matrix already had, not new
coverage of it: every one of these runs is `-gpu host`, which is row one. The table is
still seven modes measured once each, and `angle_indirect` and `swangle_indirect` are
still single measurements. What the sweep adds is that the mode the harness defaults to
survives four consecutive AVD creations across four API levels, which is the thing a
one-shot boot test could not tell you.

**Build before you sweep.** `e2e-run.sh` wraps gradle in `timeout -k 30s 1200`, and that
budget is meant to cover a test run, not a compile. A fresh checkout that starts the sweep
straight away spends API 33's twenty-minute budget on Kotlin, D8, R8 and the FFmpeg
libraries first, and a loaded machine can trip the wrapper before a single test executes —
which arrives as a WEDGED level with no XML, looking like a device problem it is not.
Running

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest -PabiFilters=x86_64
```

first costs about 90 seconds and makes every level report `1 executed, 67 up-to-date`,
so the wrapper only ever covers the part it was sized for. Use the same `-PabiFilters`
value the harness does, or the sweep rebuilds a different variant.

**It is not evidence that the device pinning works.** The Pixel disconnected from USB at
19:40:41, five seconds before the sweep started (`usb 2-2: USB disconnect` in the journal,
and nothing Google-branded in `lsusb` afterwards), so all four levels ran with exactly one
device attached and `ANDROID_SERIAL` had nothing to disambiguate. The XML filenames name
`lmc_e2e_apiNN(AVD)` and the console says `Starting 49 tests on lmc_e2e_apiNN(AVD)`, which
establishes what ran where — but the two-device case that motivated the pinning, and the
`--serial` bug above, are still only established by the earlier measurements. Re-check the
`ran on/report:` lines the next time the phone is plugged in.


## What was ruled out, and how

**KVM group membership.** Not a factor. `/dev/kvm` is `crw-rw-rw-` (mode 0666), and
`emulator -accel-check` reports `KVM (version 12) is installed and usable` while the
user is not in the `kvm` group. Runs that crashed and runs that booted both had KVM.

**"Both GPU backends."** The PR body's phrase described two samples of the same backend.
Every historical core on this host used `swiftshader_indirect`, `off`, or no `-gpu` flag
at all (which is `auto`, which is SwiftShader when headless). Command lines checked:
PIDs 9947, 12546, 171495, 175785, 176218, 189722, 243631. Not one of them used
`-gpu host`. The claim was never tested against the mode that works.

**Headless versus windowed.** Not the discriminator. Core 9947 is
`qemu-system-x86_64` (the windowed binary, not `-headless`) with
`-gpu swiftshader_indirect`, and it crashed the same way. The renderer decides the
outcome; the window does not. Windowed `-gpu host` was not separately measured, because
windowed `auto` on a machine with a GPU selects `host` anyway — which is why AVDs
launched from Android Studio's device manager have presumably always worked here.

**A newer kernel against an older emulator, or bundled-library collisions.** The core's
`info sharedlibrary` shows the emulator loading its own bundled `libc++`, `libGLESv2`
and friends alongside Fedora's `libc`, `libdrm`, `libX11` and Mesa — but the fault is
not a symbol clash or an ABI mismatch. It is a `PROT_EXEC` request that a policy refused,
and the same binaries against the same kernel boot fine under `-gpu host`.

**Guest-side causes.** Not applicable. The process that dies is the *host* qemu process,
before `sys.boot_completed` is ever set. Nothing in the guest — system image variant,
RAM, disk size, ATD versus `google_apis` — can influence a host-side `mprotect` denial,
so none of those axes was varied. (The API 37 failure in
[`api-37-emulator-crash.md`](api-37-emulator-crash.md) is genuinely guest-side — there the host
emulator survives and the guest's `surfaceflinger` aborts — but it is **not** unrelated, as this
paragraph originally claimed. Both are decided by the renderer, in opposite directions: below 37
you must avoid SwiftShader GLES and `-gpu host` is the answer; at 37 you must avoid the *host* GL
translator and `-gpu host` is the thing that never boots.)

**Turning the SELinux boolean on** — deliberately *not* done, though it would almost
certainly work:

```
$ getsebool selinuxuser_execheap
selinuxuser_execheap --> off
```

`sudo setsebool -P selinuxuser_execheap on` would grant every unconfined process on the
machine the right to execute heap memory, permanently, to accommodate one renderer in
one tool that has three working alternatives. It also needs root, which would make
`tools/local-emulator/run-e2e.sh` require `sudo` to run tests. Changing a GPU flag costs
nothing and is reversible per-invocation. If someone does flip it, the harness notices
and says so, and the refusal list in it can be relaxed.

## Reproducing it

Two commands, one AVD, opposite outcomes:

```bash
emulator -avd mc_api34 -no-window -gpu swiftshader_indirect -no-snapshot   # exit 139
emulator -avd mc_api34 -no-window -gpu host               -no-snapshot   # boots
```

To see the denial and the core for the failing one:

```bash
journalctl --since "5 min ago" | grep 'avc: .*denied'
coredumpctl list --since "5 min ago" | grep qemu
coredumpctl gdb <PID>        # then: bt, x/8i $pc, info proc mappings
```

`tools/local-emulator/run-e2e.sh` runs both of those probes automatically whenever a
boot or a test run fails, because they are what turned this diagnosis around and they
are easy to forget to look at.

## When to revisit

- **If SwiftShader stops needing `execheap`.** Upstream SwiftShader moved from Subzero to
  an LLVM JIT that maps code with a memfd rather than the heap; if a future emulator
  bundles that, `swiftshader_indirect` would start working here and the refusal list in
  `tools/local-emulator/run-e2e.sh` should be trimmed. The version that fails is
  "SwiftShader 4.0.0.1" as reported by the GLES translator.
- **If `-gpu host` regresses** after a Mesa or kernel update, fall back to
  `GPU_MODE=swangle_indirect`, which needs no GPU at all.
- **API 37 needs the opposite renderer, and this file used to say it needed nothing.** The
  original bullet here read "This changes nothing about API 37"; that turned out to be wrong.
  The API 37 images abort `surfaceflinger` under the *host* GL translator and boot under ANGLE —
  the exact mirror of the rule above — and `run-e2e.sh` therefore picks the renderer per API
  level. See [`api-37-emulator-crash.md`](api-37-emulator-crash.md), which was rewritten on
  2026-08-22 with the seven-run matrix. API 37 still must be checked on the physical Pixel 10 Pro
  XL before each release.

## Correction owed to `CLAUDE.md`

`CLAUDE.md` currently heads a section **"Instrumented tests do not run locally"** and gives
"two independent reasons", the first being:

> - **Emulators segfault on this host.** qemu dies on every AVD. Instrumented tests run on
>   CI or on the physical Pixel, never in a local emulator.

That bullet is now wrong, and the heading above it is wrong with it. Only the API 37 reason
survives, and it was never about local emulators specifically — it is equally true in CI.
The claim is also load-bearing further up the file, where "the instrumented suite cannot run
on this machine" is the stated reason `compileDebugAndroidTestKotlin` is in the pre-commit
list. That reason weakens but the advice does not: compiling androidTest is still the fast
check, and nobody wants to boot four emulators to find a syntax error.

Proposed replacement for the section, offered for review rather than applied here:

> ## Instrumented tests run locally, with one renderer caveat
>
> `tools/local-emulator/run-e2e.sh` runs the suite on API 33–36 on a local emulator.
> Two things to know:
>
> - **Do not let the emulator choose its own renderer.** SwiftShader's GLES JIT needs
>   `execheap`, which Fedora's SELinux policy denies, so the emulator segfaults with
>   exit 139 before boot. That is what `-gpu swiftshader_indirect` does — and also what
>   `auto` (the default), `off` and `guest` do when headless. `-gpu host` works, and the
>   harness both picks it and refuses the others. `docs/local-emulator.md` has the
>   backtrace and the mode matrix.
> - **API 37 needs the opposite renderer, and SystemUI turned off.** Both `android-37.0` and
>   `android-37.1` abort surfaceflinger inside their own gralloc mapper, and init SIGKILLs
>   zygote each time. Under `-gpu host` they never boot; under `-gpu swangle_indirect` they
>   boot, and disabling SystemUI removes the trigger. `run-e2e.sh` does all of that per level,
>   and the local API 37 result is two failures and the two usual skips, not a clean run. CI's
>   matrix still stops at 36.
>   `docs/api-37-emulator-crash.md` has the matrix and the reasoning. **API 37 needs a manual
>   check on the Pixel 10 Pro XL before each release.**

The wording is worth getting right rather than merely correcting, because the original was
not a careless sentence — it was a reasonable inference from three crashes, written down
confidently, and then believed for long enough to shape how the project tests. The useful
lesson to preserve is narrower than "emulators are fine now": an `exit 139` with no
backtrace attached is not a diagnosis, and on a host that runs systemd-coredump and
SELinux, the backtrace and the denial were both sitting there the whole time.
