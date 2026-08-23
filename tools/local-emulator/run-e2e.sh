#!/usr/bin/env bash
#
# Runs the instrumented suite on a local emulator, on this workstation, for one or more
# API levels.
#
# Usage:  tools/local-emulator/run-e2e.sh [API ...]        # default: 33 34 35 36 37
#
# API levels are the labels below, not SDK ints: 33-36, plus `37` (= `37.0`) and `37.1`.
#
#   GPU_MODE=              force one renderer on every level; unset means per-API (gpu_for_api)
#   EMULATOR_PORT=5560     console port, so the serial is deterministic
#   BOOT_TIMEOUT=300       seconds to wait for sys.boot_completed
#   KEEP_AVD=1             do not delete an AVD this script created
#
# WHY THIS EXISTS, AND WHAT IT DELIBERATELY DOES NOT DO
#
# It is a *launcher*, not a second test harness. The diagnostics -- the FAILED-vs-WEDGED
# split, the SIGQUIT thread dump, the streamed logcat, `|| true` on every probe -- already
# exist in .github/scripts/e2e-run.sh and are the valuable part. That script assumes only
# an already-booted emulator and an `adb` that resolves to it, so it runs here unmodified;
# this file boots the emulator, points adb and Gradle at it, and calls it. Forking it would
# have produced two copies that drift, and the CI copy is the one exercised every day.
#
# Its GitHub-isms (`::group::`, `::error::`) are harmless noise in a local terminal, and
# RUNNER_TEMP already falls back to /tmp.
#
# THE ONE THING THIS HOST NEEDS THAT CI DOES NOT: a renderer that is not SwiftShader's
# GLES. Fedora's SELinux policy denies `execheap` to unconfined processes
# (`selinuxuser_execheap` is off), SwiftShader's Reactor JIT emits code onto the heap and
# mprotects it executable, the mprotect is refused, and the emulator segfaults the moment
# it calls into the generated routine -- exit 139, every time, before boot completes.
#
# That makes CI's own `-gpu swiftshader_indirect` exactly wrong here, and -- less obviously
# -- so are `auto`, `off` and `guest`, which all resolve to SwiftShader GLES under
# `-no-window` on this host. The refusal list below is not a style preference; each of
# those modes was measured crashing. docs/local-emulator.md has the backtrace, the faulting
# page's RW-without-E segment flags, and the full mode matrix.
#
# AND THE ONE THING API 37 NEEDS THAT 33-36 DO NOT: the opposite renderer. On the API 37
# images the guest's Gralloc5 mapper aborts surfaceflinger from RegionSamplingThread
# (`Assertion failed: !rcEnc->featureInfo()->hasReadColorBufferDma`). Under `-gpu host` that
# repeats every few seconds and the device never boots; under ANGLE it fires a handful of
# times and the boot survives. So `host` is required below 37 and forbidden at 37, which is
# why the renderer is chosen per level in gpu_for_api rather than set once.
# docs/api-37-emulator-crash.md has that matrix.
#
# THE OTHER LOCAL-ONLY HAZARD: a physical Pixel is usually plugged into this machine, so
# `adb` is ambiguous in a way it never is on a runner, and an unpinned run would install
# and execute this suite on the phone. Every path below pins the emulator serial.
#
# Gradle is pinned with ANDROID_SERIAL and NOT with the `--serial` task option, which reads
# like the better tool -- it is documented to fail when the device is missing, where the env
# var just falls back. It is unusable: `--serial` is broken in AGP 9.3.1, which calls
# `remove()` on an ImmutableList and dies before it reaches any device.
#
#   java.lang.UnsupportedOperationException
#     at com.google.common.collect.ImmutableCollection.remove(ImmutableCollection.java:280)
#     at DeviceProviderInstrumentTestTask.getFilteredDevices(...:519)
#
# (Measured with two devices attached and one filtered out, which is the case that reaches
# the `remove()`. A single matching device probably never does -- untested, and irrelevant
# here, since two devices is exactly the situation that needed pinning.)
#
# ANDROID_SERIAL takes an entirely different path -- ConnectedDeviceProvider splits it and
# keeps devices by `Set.contains(serial)`, building a new list rather than mutating one --
# so it works. summarise_results below re-checks the device from the report afterwards,
# since the env var offers no up-front guarantee.
#
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT" || exit 1

export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

# Empty means "let each level pick" -- see gpu_for_api. Setting GPU_MODE forces one renderer
# on every level, which is what you want when measuring a mode, not when running the suite.
GPU_MODE="${GPU_MODE:-}"
EMULATOR_PORT="${EMULATOR_PORT:-5560}"
BOOT_TIMEOUT="${BOOT_TIMEOUT:-300}"
SERIAL="emulator-${EMULATOR_PORT}"
APIS=("$@")
[ "${#APIS[@]}" -eq 0 ] && APIS=(33 34 35 36 37)

# Matches CI. `disk-size: 8G` because the FFmpeg libraries do not fit the default userdata
# partition; `ram-size: 2560M` because the emulator's own floor varies by API level and
# 2560 is the highest of them, so no level ends up with less than it had. Both are
# explained at length in status_check.yml -- keep them in step with it.
DISK_SIZE_BYTES=8589934592
RAM_SIZE_MB=2560

RESULTS_DIR="app/build/outputs/androidTest-results"
LOG_DIR="${TMPDIR:-/tmp}/lmc-local-e2e"
mkdir -p "$LOG_DIR"

# ---------------------------------------------------------------- renderer preflight ---
if [ -n "$GPU_MODE" ]; then
  case "$GPU_MODE" in
    swiftshader_indirect | auto | off | guest)
      echo "REFUSING to launch with -gpu $GPU_MODE."
      echo "On this host that resolves to SwiftShader's GLES, whose JIT is denied execheap by"
      echo "SELinux; the emulator segfaults (exit 139) before boot. See docs/local-emulator.md."
      echo "Working modes: host, angle_indirect, swangle_indirect."
      exit 2
      ;;
    host | angle_indirect | swangle_indirect) ;;
    *)
      echo "Unrecognised GPU_MODE '$GPU_MODE'. Known-good: host, angle_indirect, swangle_indirect."
      exit 2
      ;;
  esac
fi

# A courtesy, not a gate: the boolean being on means SwiftShader would work too, and the
# refusal list above could be relaxed. It is off on a stock Fedora.
if command -v getsebool > /dev/null 2>&1; then
  if [ "$(getsebool selinuxuser_execheap 2> /dev/null | awk '{print $3}')" = "on" ]; then
    echo "note: selinuxuser_execheap is ON, so SwiftShader modes would work here too."
  fi
fi

# --------------------------------------------------------------------------- helpers ---
emu_adb() { adb -s "$SERIAL" "$@"; }

# The two probes that turned the original diagnosis around. CI has no use for them -- a
# runner has neither systemd-coredump nor SELinux in enforcing mode -- but on this host they
# are the difference between "it failed" and knowing why.
host_forensics() {
  local since="$1"
  echo "--- host: qemu coredumps since $since ---"
  coredumpctl list --since "$since" --no-pager 2> /dev/null | grep -i qemu || echo "  (none)"
  echo "--- host: SELinux denials since $since ---"
  journalctl --since "$since" --no-pager 2> /dev/null | grep -E 'avc: .*denied' | tail -10 || echo "  (none)"
}

# The API 37 counterpart of host_forensics. `-gpu host` there aborts surfaceflinger in a loop
# and the device never boots; the working renderers abort it a few times and survive. Either way
# the count is the number to look at, and the crash buffer is where it lives -- so print it on
# every 37 level, not only on the failure path, because a level that passed with 40 aborts is
# telling you something a level that passed with 1 is not.
guest_forensics() {
  local api="$1" n
  case "$api" in 37 | 37.*) ;; *) return 0 ;; esac
  n="$(emu_adb logcat -d -b crash 2> /dev/null | grep -c 'hasReadColorBufferDma')"
  echo "  surfaceflinger hasReadColorBufferDma aborts: ${n:-?}  (docs/api-37-emulator-crash.md)"
}

# API label -> system image. API 33-36 are plain integers with a `google_apis` image. API 37
# is not: its SDK directories are dotted minor versions (`android-37.0`, `android-37.1`), there
# is no `android-37`, and from 37.1 onwards Google ships only 16 KB-page (`ps16k`) images for
# x86_64. `37` is accepted as a spelling of `37.0` because that is what people type.
image_pkg_for_api() {
  case "$1" in
    37 | 37.0) echo "system-images;android-37.0;google_apis;x86_64" ;;
    37.1) echo "system-images;android-37.1;google_apis_ps16k;x86_64" ;;
    *) echo "system-images;android-$1;google_apis;x86_64" ;;
  esac
}

# The renderer requirement is per-API and the two levels want OPPOSITE things, which is why this
# is a function and not a constant.
#
#   33-36: must NOT be SwiftShader GLES (host-side SELinux/execheap segfault) -- `host` is right.
#   37.x:  must NOT be the host GL translator. With `-gpu host` the guest's Gralloc5 mapper
#          aborts surfaceflinger in a loop and the device never boots; under ANGLE the same
#          assertion fires a handful of times and the boot survives it. Measured, not guessed --
#          docs/api-37-emulator-crash.md has the matrix.
#
# `swangle_indirect` rather than `angle_indirect` for 37: both boot, and swangle names its
# renderer outright instead of resolving through `auto`'s path.
gpu_for_api() {
  if [ -n "$GPU_MODE" ]; then
    echo "$GPU_MODE"
    return
  fi
  case "$1" in
    37 | 37.*) echo "swangle_indirect" ;;
    *) echo "host" ;;
  esac
}

# `lmc_e2e_api37.0` would be a legal AVD name but an awkward one to type and to grep for.
# `37` and `37.0` therefore give two AVD names (`lmc_e2e_api37`, `lmc_e2e_api37_0`) for the one
# image. Harmless -- two AVDs off the same system image cost only disk -- and deliberately not
# normalised, so that `run-e2e.sh 37 37.0` does not have both levels fight over one AVD.
avd_for_api() { echo "lmc_e2e_api${1//./_}"; }

ensure_avd() {
  local api="$1" avd="$2"
  local pkg
  pkg="$(image_pkg_for_api "$api")"
  local img_dir="$ANDROID_HOME/system-images/${pkg#system-images;}"
  img_dir="${img_dir//;//}"

  if avdmanager list avd -c 2> /dev/null | grep -qx "$avd"; then
    echo "  reusing existing AVD $avd"
  else
    if [ ! -d "$img_dir" ]; then
      echo "  installing $pkg"
      yes | sdkmanager --install "$pkg" > /dev/null 2>&1 || {
        echo "  FAILED to install $pkg"
        return 1
      }
    fi
    echo "  creating AVD $avd from $pkg"
    echo no | avdmanager create avd -n "$avd" -k "$pkg" -d pixel_6 --force > /dev/null 2>&1 || {
      echo "  FAILED to create $avd"
      return 1
    }
    CREATED_AVDS+=("$avd")
  fi

  # Written into config.ini rather than passed on the command line, which is how
  # reactivecircus/android-emulator-runner applies the same two settings in CI.
  local cfg="$HOME/.android/avd/${avd}.avd/config.ini"
  sed -i -e '/^disk\.dataPartition\.size=/d' -e '/^hw\.ramSize=/d' "$cfg"
  printf 'disk.dataPartition.size=%s\nhw.ramSize=%s\n' "$DISK_SIZE_BYTES" "$RAM_SIZE_MB" >> "$cfg"
}

boot_emulator() {
  local avd="$1" api="$2" gpu="$3"
  local boot_log="$LOG_DIR/emulator-api${api}.log"

  emulator -avd "$avd" -port "$EMULATOR_PORT" \
    -no-window -gpu "$gpu" -noaudio -no-boot-anim -camera-back none -no-snapshot \
    > "$boot_log" 2>&1 &
  EMU_PID=$!

  local waited=0
  while [ "$waited" -lt "$BOOT_TIMEOUT" ]; do
    sleep 5
    waited=$((waited + 5))
    if ! kill -0 "$EMU_PID" 2> /dev/null; then
      wait "$EMU_PID"
      local rc=$?
      echo "  emulator DIED after ${waited}s (exit $rc)"
      [ "$rc" -eq 139 ] && echo "  exit 139 is the SwiftShader/execheap segfault -- docs/local-emulator.md"
      echo "  emulator log: $boot_log"
      tail -20 "$boot_log"
      return 1
    fi
    if [ "$(emu_adb shell getprop sys.boot_completed 2> /dev/null | tr -d '\r\n')" = "1" ]; then
      echo "  booted in ${waited}s"
      return 0
    fi
  done
  echo "  emulator NEVER BOOTED within ${BOOT_TIMEOUT}s -- log: $boot_log"
  return 1
}

# API 37 only, and the reason API 37 can be run at all.
#
# The abort that breaks these images is reached from SurfaceFlinger's RegionSamplingThread,
# which exists only because SystemUI registers a nav-bar luma-sampling listener. Each abort
# kills surfaceflinger, and init responds by SIGKILLing zygote -- so the whole framework
# restarts underneath the test run, which arrives as `Can't find service: package` and
# `INSTRUMENTATION_ABORTED: System has crashed`. Under the host GL renderer that repeats
# forever; under ANGLE it is roughly one every fifteen seconds, which a five-minute suite does
# not survive either.
#
# Removing the listener removes the whole chain. Measured on android-37.0 under
# swangle_indirect: 10-11 aborts per 150 s idle with SystemUI running, and 0 in 180 s with it
# disabled, framework services up throughout.
#
# THIS IS A DEVIATION, and it is deliberately loud rather than silent. The API 37 leg does not
# run the same device configuration as API 33-36 or as the Pixel. It is defensible only
# because nothing in this suite touches SystemUI -- these are Media3, FFmpeg and WorkManager
# tests -- and because the alternative is no API 37 coverage at all. Anything that ever does
# depend on system UI must not trust this leg. docs/api-37-emulator-crash.md explains why.
#
# The retry loop is not defensive padding: at the moment boot_completed flips, the framework
# may be in one of its restarts and `pm` is simply not published yet. The first attempt at this
# failed exactly that way, with `cmd: Can't find service: package`.
#
# The framework restart at the end is not optional, and finding that out cost a run. By the
# time `sys.boot_completed` flips, SystemUI has already registered its region-sampling listener,
# and `pm disable-user` does not retract a registration that already happened -- it only stops
# the package being started again. So the first attempt disabled SystemUI, reported success, and
# then died exactly as before with `Starting 0 tests` and four more aborts. `stop; start` cycles
# zygote deliberately, and the framework that comes back up does not start SystemUI at all.
disable_region_sampling() {
  local api="$1" out i before after
  case "$api" in 37 | 37.*) ;; *) return 0 ;; esac

  out=""
  for i in $(seq 1 20); do
    out="$(emu_adb shell pm disable-user --user 0 com.android.systemui 2>&1 | tr -d '\r')"
    case "$out" in
      *"new state: disabled"*)
        echo "  SystemUI disabled on attempt $i"
        break
        ;;
    esac
    out=""
    sleep 5
  done
  if [ -z "$out" ]; then
    echo "  WARNING: could not disable SystemUI after 20 attempts."
    echo "  Expect INSTRUMENTATION_ABORTED -- docs/api-37-emulator-crash.md"
    return 0
  fi

  echo "  restarting the framework so the region-sampling listener goes with it"
  emu_adb shell stop > /dev/null 2>&1
  emu_adb shell start > /dev/null 2>&1
  # One blocking wait on the device, then confirm the services the test runner actually calls.
  emu_adb wait-for-device shell \
    'while [[ -z $(getprop sys.boot_completed) ]]; do sleep 2; done' > /dev/null 2>&1
  for i in $(seq 1 30); do
    if emu_adb shell service check package 2> /dev/null | grep -q ': found' \
      && emu_adb shell service check activity 2> /dev/null | grep -q ': found'; then
      break
    fi
    sleep 5
  done

  # Prove it worked rather than assume it. Zero new aborts over this window is what makes the
  # difference between a run that completes and one that reports `Starting 0 tests`.
  before="$(emu_adb logcat -d -b crash 2> /dev/null | grep -c 'hasReadColorBufferDma')"
  emu_adb shell 'sleep 45' > /dev/null 2>&1
  after="$(emu_adb logcat -d -b crash 2> /dev/null | grep -c 'hasReadColorBufferDma')"
  echo "  quiet check: $((after - before)) new surfaceflinger aborts in 45 s (want 0)"
  if [ "$((after - before))" -ne 0 ]; then
    echo "  WARNING: region sampling is still live; the run may not survive."
  fi
  return 0
}

# CI gets this from the action's `disable-animations: true`.
disable_animations() {
  local s
  for s in window_animation_scale transition_animation_scale animator_duration_scale; do
    emu_adb shell settings put global "$s" 0.0 > /dev/null 2>&1 || true
  done
}

stop_emulator() {
  emu_adb emu kill > /dev/null 2>&1
  local waited=0
  while kill -0 "${EMU_PID:-0}" 2> /dev/null && [ "$waited" -lt 30 ]; do
    sleep 2
    waited=$((waited + 2))
  done
  kill -9 "${EMU_PID:-0}" 2> /dev/null
  wait "${EMU_PID:-0}" 2> /dev/null
}

# The XML is authoritative. The console counter double-counts skips, so a run that reports
# "42 tests" on stdout can be 40 in the report.
#
# It also names the device it ran on, in the file name and in the suite's `hostname`. That is
# printed rather than just counted, because it is the only after-the-fact proof that this
# level ran fresh and ran on the emulator -- see the ANDROID_SERIAL note in the header.
summarise_results() {
  local api="$1"
  python3 - "$api" "$RESULTS_DIR" << 'PY'
import glob, os, sys, xml.etree.ElementTree as ET
api, results_dir = sys.argv[1], sys.argv[2]
t = f = e = s = 0
devices = set()
files = sorted(glob.glob(os.path.join(results_dir, "**", "TEST-*.xml"), recursive=True))
for path in files:
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError:
        continue
    suites = [root] if root.tag == "testsuite" else list(root.iter("testsuite"))
    for suite in suites:
        t += int(suite.get("tests", 0)); f += int(suite.get("failures", 0))
        e += int(suite.get("errors", 0)); s += int(suite.get("skipped", 0))
        if suite.get("hostname"):
            devices.add(suite.get("hostname"))
    devices.add(os.path.basename(path))
if not files:
    print(f"API {api}: no result XML found under {results_dir}")
else:
    print(f"API {api}: tests={t} failures={f} errors={e} skipped={s}")
    for d in sorted(devices):
        print(f"    ran on/report: {d}")
PY
}

# ------------------------------------------------------------------------------ main ---
CREATED_AVDS=()
SUMMARY=()
overall=0

for api in "${APIS[@]}"; do
  avd="$(avd_for_api "$api")"
  gpu="$(gpu_for_api "$api")"
  started="$(date '+%Y-%m-%d %H:%M:%S')"
  echo "=============================================================="
  echo "API $api  (avd=$avd gpu=$gpu serial=$SERIAL)"
  echo "  image: $(image_pkg_for_api "$api")"
  echo "=============================================================="

  if ! ensure_avd "$api" "$avd"; then
    SUMMARY+=("API $api: AVD SETUP FAILED")
    overall=1
    continue
  fi

  if ! boot_emulator "$avd" "$api" "$gpu"; then
    host_forensics "$started"
    guest_forensics "$api"
    SUMMARY+=("API $api: BOOT FAILED")
    overall=1
    stop_emulator
    continue
  fi

  disable_region_sampling "$api"
  disable_animations
  rm -rf "$RESULTS_DIR"

  # ANDROID_SERIAL steers both e2e-run.sh's own bare `adb` calls and Gradle's device choice.
  # --rerun because each level must actually re-execute: without it a task Gradle considers
  # up-to-date would leave the previous level's XML in place, and every row of the summary
  # would report the same numbers.
  export ANDROID_SERIAL="$SERIAL"
  export E2E_EXTRA_GRADLE_ARGS="--rerun"
  bash .github/scripts/e2e-run.sh "$api"
  rc=$?
  unset ANDROID_SERIAL E2E_EXTRA_GRADLE_ARGS

  line="$(summarise_results "$api")"
  guest_forensics "$api"
  # API 37 is in the default list on purpose, and it is expected to be red. Leaving it out would
  # put the level back where this whole exercise found it -- untested and unlooked-at -- but a
  # summary that just says "2 failures" with no explanation trains people to ignore the exit
  # code. So the row says which two, and a THIRD failure is then obviously new.
  case "$api" in
    37 | 37.*)
      line="$line
    expected here: 2 failures, both Media3EngineTest, on c2.goldfish.h264.decoder.
    A third is new -- docs/api-37-emulator-crash.md"
      ;;
  esac
  if [ "$rc" -ne 0 ]; then
    line="$line  [gradle exit $rc]"
    overall=1
    host_forensics "$started"
  fi
  SUMMARY+=("$line")
  stop_emulator
done

if [ "${KEEP_AVD:-0}" != "1" ]; then
  for avd in ${CREATED_AVDS[@]+"${CREATED_AVDS[@]}"}; do
    avdmanager delete avd -n "$avd" > /dev/null 2>&1
  done
fi

echo
echo "===================== LOCAL E2E SUMMARY ======================"
printf '%s\n' ${SUMMARY[@]+"${SUMMARY[@]}"}
echo "=============================================================="
exit "$overall"
