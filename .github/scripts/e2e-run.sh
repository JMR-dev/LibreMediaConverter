#!/usr/bin/env bash
#
# Runs the instrumented suite on an already-booted emulator, and makes a failure
# diagnosable without a re-run.
#
# Adapted from LibreMail's CI emulator instrumentation. Its lesson, learned there over
# several wedged merge queues, is that a red E2E leg with nothing but "exit 1" in the log
# costs more than the failure itself -- so every failure path here leaves evidence behind.
#
# WHY THIS IS A FILE rather than inline YAML: reactivecircus/android-emulator-runner splits
# its `script:` input on newlines and runs each line as its own `sh -c`. Shell functions,
# `if` blocks and traps cannot survive that, which is why the previous version had its whole
# failure handler crammed onto one unreadable line. One line calls this; this can breathe.
#
# Two failure shapes, deliberately handled differently:
#
#   FAILED  -- gradle returned non-zero. The reports say which test and why, so capture the
#              device and runner state around it.
#   WEDGED  -- gradle never returned and the wrapper timeout killed it. There is no report at
#              all, so the evidence has to be taken from the live device: what test was
#              running, and what every process was doing. SIGQUIT is the important part -- ART
#              dumps full thread stacks to logcat and /data/anr, which is how you tell a
#              deadlocked test from a stuck MediaCodec from an emulator that stopped answering.
#
# Every probe is guarded with `|| true`. A diagnostic must never be the thing that turns a run
# red -- notably, a grep that matches nothing exits 1.
set -uo pipefail

LABEL="${1:-unknown}"
APP_ID="org.libremediaconverter"
TEST_ID="org.libremediaconverter.test"

TMP="${RUNNER_TEMP:-/tmp}"
LOGCAT_LOG="$TMP/logcat-api${LABEL}.txt"
DIAG_LOG="$TMP/diagnostics-api${LABEL}.txt"
WEDGE_LOG="$TMP/wedge-diagnostics-api${LABEL}.txt"
# Gradle's own output, captured to a file as well as the step log, because the run-shape report
# below has to parse it. Uploaded with the diagnostics, so a report that reads wrong can be
# checked against what it read.
GRADLE_LOG="$TMP/gradle-api${LABEL}.txt"

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"

# ~5 min is a healthy leg (measured across API 33-36), and this wraps only the gradle client,
# a subset of that. 20 min is generous enough never to trip on a slow-but-working run, and far
# enough under the job's 60-min cap that a genuine wedge still leaves time to capture it.
WEDGE_TIMEOUT=1200

# ---------------------------------------------------------------------------
# API 37 only, and nothing else sets it, so this is inert everywhere it is not wanted --
# the same shape as E2E_EXTRA_GRADLE_ARGS below. The other four E2E legs run byte-identical
# commands with it unset.
#
# WHY IT RUNS HERE, BEFORE THE LOGCAT STREAM: `adb shell stop` ends the `adb logcat` started
# below, and nothing restarts it, so a disable performed after that point would cost this leg
# its whole diagnostic story for the part of the run that matters. Everything this function
# counts comes from `adb logcat -d -b crash`, which is a fresh read each time and independent
# of the stream.
#
# WHAT IT IS FOR: the android-37.x images abort surfaceflinger from RegionSamplingThread inside
# their own gralloc mapper (docs/api-37-emulator-crash.md). surfaceflinger is a critical service,
# so init SIGKILLs zygote with it and the framework restarts under the run -- Gradle then reports
# `cmd: Can't find service: package` and `Starting 0 tests`. RegionSamplingThread exists only
# because SystemUI registers a nav-bar luma-sampling listener, so removing the package removes
# the whole chain. Measured cadence of those kills: 20-90 s apart, median 60-70 s, three to five
# in a four-minute window -- fast enough that install and instrumentation start-up do not fit
# inside one gap.
#
# NOTHING HERE TRUSTS A COMMAND'S OWN REPORT, and that is not paranoia: of four runs of an
# earlier one-shot version, one (32646029143) reported `new state: disabled-user` and then
# started SystemUI eight more times, with ten more aborts. `pm disable-user` can be accepted by
# a system_server that is SIGKILLed before the state is written, and `pm disable-user` does not
# retract SystemUI's existing region-sampling registration either -- by the time boot completes
# it has already registered, so only a framework restart brings back a SystemUI-less
# surfaceflinger. Hence: disable, take the framework DOWN and confirm system_server is really
# gone (an earlier probe asked `service check` 0.3 s after `stop` and got `found` from the
# system_server that was still exiting, so its wait was not a wait), bring it back, verify the
# package against `pm list packages -d`, and require a 45 s window with zero new aborts.
# Three rounds, because one is not reliable and the failure is silent.
# ---------------------------------------------------------------------------
# Every device probe below is aimed at an emulator that has already failed, and on the wedge path
# at one that has just finished proving it stopped answering. So each is bounded in time as well as
# in exit status.
#
# `|| true` guards a probe that exits non-zero. It does nothing about one that never exits -- which
# is how #122's wedge path spent 36 minutes after printing its own diagnosis, lost the job to the
# 60-minute cap, and so reported `cancelled` instead of the wedge's own status. The header's rule
# that "a diagnostic must never be the thing that turns a run red" was enforced for exit codes and
# not for time; this is the other half of it.
#
# 20s is far more than any of these needs on a healthy device and far less than any of them costs
# on a dead one. `-k` because adb itself can ignore the first signal when its server is wedged.
ADB_PROBE_TIMEOUT=20
adbq() { timeout -k 5s "$ADB_PROBE_TIMEOUT" adb "$@"; }

count_aborts() { adb logcat -d -b crash 2> /dev/null | grep -c 'hasReadColorBufferDma'; }
systemui_disabled() { adb shell pm list packages -d 2> /dev/null | grep -q 'com.android.systemui'; }

disable_region_sampling() {
  local round=1 i out before after
  while [ "$round" -le 3 ]; do
    echo "--- SystemUI disable, round $round ---"
    for i in $(seq 1 10); do
      out="$(adb shell pm disable-user --user 0 com.android.systemui 2>&1 | tr -d '\r')"
      echo "  pm attempt $i: $out"
      case "$out" in *"new state: disabled"*) break ;; esac
      sleep 5
    done

    echo "  restarting the framework"
    adb shell stop
    for i in $(seq 1 20); do
      [ -z "$(adb shell pidof system_server 2> /dev/null | tr -d '\r\n')" ] && break
      sleep 2
    done
    echo "  system_server down after ~$((i * 2)) s"
    adb shell start
    for i in $(seq 1 30); do
      if adb shell service check package 2> /dev/null | grep -q ': found' \
        && adb shell service check activity 2> /dev/null | grep -q ': found' \
        && [ -n "$(adb shell pidof system_server 2> /dev/null | tr -d '\r\n')" ]; then
        echo "  services back after ~$((i * 5)) s"
        break
      fi
      sleep 5
    done

    if systemui_disabled; then
      echo "  verified: com.android.systemui is in pm list packages -d"
    else
      echo "  NOT DISABLED after the restart -- the package state did not survive"
      round=$((round + 1))
      continue
    fi

    before="$(count_aborts)"
    sleep 45
    after="$(count_aborts)"
    echo "  abort rate, SystemUI disabled: $((after - before)) new in 45 s (total ${after:-0})"
    [ "$((after - before))" -eq 0 ] && break
    echo "  still aborting after round $round"
    round=$((round + 1))
  done

  # A warning rather than an exit. If the disable did not take, the run is about to report
  # `Starting 0 tests` and fail on its own -- and it will do so with the logcat, the crash
  # buffer and the diagnostics attached, which is more useful than dying here with none of it.
  if systemui_disabled; then
    echo "  final state: SystemUI disabled"
  else
    echo "::warning::E2E api${LABEL}: SystemUI is still enabled -- expect INSTRUMENTATION_ABORTED"
  fi
  return 0
}

if [ "${E2E_DISABLE_SYSTEM_UI:-}" = "1" ]; then
  echo "::group::E2E api${LABEL} -- removing the region-sampling listener"
  disable_region_sampling
  echo "::endgroup::"
fi

# Stream logcat from now until the step ends, into a file that survives to the artifact upload.
# Without this, a failure that happens on-device leaves nothing behind: `adb logcat -d` at the
# end only has whatever is still in the ring buffer, and a chatty test run evicts the cause.
echo "===== logcat (api${LABEL}) =====" >> "$LOGCAT_LOG"
adb logcat -v time >> "$LOGCAT_LOG" 2>&1 &
LOGCAT_PID=$!

dump_diagnostics() {
  {
    echo "===== E2E api${LABEL} failure diagnostics -- $(date -u +%FT%TZ) ====="
    echo "--- adb devices ---";                adbq devices -l 2>&1 || true
    echo "--- guest memory ---";               adbq shell cat /proc/meminfo 2>&1 | grep -E 'MemTotal|MemAvailable|SwapTotal' || true
    echo "--- guest storage ---";              adbq shell df /data 2>&1 || true
    echo "--- is the app even installed? ---"; adbq shell pm list packages 2>&1 | grep -a libremedia || true
    echo "--- native crashes ---";             adbq logcat -d -b crash 2>&1 | tail -80 || true
    echo "--- runner: kvm ---";                ls -l /dev/kvm 2>&1 || true
    echo "--- runner: memory ---";             free -h 2>&1 || true
    echo "--- runner: disk ---";               df -h 2>&1 || true
  } >> "$DIAG_LOG" 2>&1 || true

  # Also to the step log, so the common case needs no artifact download.
  echo "----- FAILURE SUMMARY (api${LABEL}) -----"
  adbq shell cat /proc/meminfo 2>&1 | grep -E 'MemTotal|MemAvailable' || true
  echo "--- native crashes (tail 60) ---"
  adbq logcat -d -b crash 2>&1 | tail -60 || true
}

capture_wedge() {
  {
    echo "==================================================================="
    echo "===== E2E WEDGE -- api${LABEL} -- $1"
    echo "===== $(date -u +%FT%TZ) -- after ${WEDGE_TIMEOUT}s wrapper timeout"
    echo "==================================================================="
    # The single most useful line: which test was in flight when everything stopped.
    echo "--- running/last instrumented test (logcat TestRunner) ---"
    grep -a TestRunner "$LOGCAT_LOG" 2>/dev/null | tail -25 || true
    echo "--- boot state ---"
    adbq shell getprop sys.boot_completed 2>&1 || true
    echo "--- are the binder services published? ---"
    for svc in input window activity media.player; do
      echo "  service check $svc:"; adbq shell service check "$svc" 2>&1 || true
    done
    APP_PID="$(adbq shell pidof "$APP_ID" 2>/dev/null | tr -d '\r')" || true
    TEST_PID="$(adbq shell pidof "$TEST_ID" 2>/dev/null | tr -d '\r')" || true
    echo "--- pids --- app: ${APP_PID:-<none>}  test: ${TEST_PID:-<none>}"
    # SIGQUIT makes ART dump every thread's stack to logcat and /data/anr. This is what
    # distinguishes a deadlocked test from a stuck native encode from a dead device.
    echo "--- SIGQUIT thread dumps ---"
    for pid in $APP_PID $TEST_PID; do
      [ -n "$pid" ] && adbq shell kill -3 "$pid" 2>&1 || true
    done
    sleep 5
    echo "--- /data/anr/* ---"
    adbq shell 'cat /data/anr/* 2>/dev/null' 2>&1 || true
    echo "--- dumpsys activity ---"; adbq shell dumpsys activity 2>&1 || true
    echo "--- dumpsys window ---";   adbq shell dumpsys window 2>&1 || true
    # FFmpeg and Media3 both run through MediaCodec; a wedged transcode shows up here.
    echo "--- dumpsys media.player ---"; adbq shell dumpsys media.player 2>&1 || true
    echo "--- logcat -d (tail 400, includes the SIGQUIT dump) ---"
    adbq logcat -d 2>&1 | tail -400 || true
  } >> "$WEDGE_LOG" 2>&1 || true
  echo "::warning::E2E api${LABEL} WEDGED ($1) -- see the wedge-diagnostics-api${LABEL} artifact"
}

echo "::group::E2E api${LABEL}"
# Bounded like the probes in the two diagnostic functions, and for the same reason. This one runs
# against a freshly booted emulator rather than a wedged one, so it is the least likely of them to
# hang -- but it is still a `|| true` diagnostic, and the rule this file now states is that a
# diagnostic must never be the thing that ends the leg.
adbq shell cat /proc/meminfo 2>&1 | grep -E 'MemTotal|MemAvailable|SwapTotal' || true

status=0
# -k 30s SIGKILLs a gradle client that ignores SIGTERM. The wrapper covers ONLY the foreground
# gradle client -- never the emulator, which the action owns -- so it cannot hang the leg.
#
# E2E_EXTRA_GRADLE_ARGS is unset in CI, so this expands to nothing and the command is exactly
# what it has always been. It exists for tools/local-emulator/run-e2e.sh, which reuses this
# script rather than forking it: that runs several API levels back to back against one checkout
# and passes `--rerun`, so a level cannot be skipped as up-to-date and report the previous
# level's results as its own. CI gets a fresh runner per level and does not need it.
#
# `2>&1 | tee`, and the `2>&1` is the load-bearing half. The step log merges both streams, so
# reading one cannot tell you which stream a line came from -- and the single line the report
# below needs most, `Test run failed to complete. ... INSTRUMENTATION_ABORTED`, is not on
# stdout. Capturing stdout alone would leave the report saying "completed cleanly: yes" forever,
# which is precisely the comparison that cannot fire. pipefail is already set and tee exits 0,
# so the pipeline's status is still gradle's -- including the 124 that means the wrapper fired.
#
# `tee` and not `tee -a`, unlike the logcat above: CI gets a fresh runner per leg, but
# tools/local-emulator/run-e2e.sh reuses one machine, and an appended log would have the report
# reading the PREVIOUS run of the same API level. The console goes plain rather than showing
# gradle's live progress bar, which is what it already did in CI.
# shellcheck disable=SC2086
timeout -k 30s "$WEDGE_TIMEOUT" \
  ./gradlew :app:connectedDebugAndroidTest -PabiFilters=x86_64 --stacktrace \
  ${E2E_EXTRA_GRADLE_ARGS:-} 2>&1 | tee "$GRADLE_LOG" || status=$?
echo "::endgroup::"

# Whether the wrapper timeout fired, decided ONCE. 124 is `timeout` saying it killed the
# command, and two places downstream need that fact: capture_wedge below, and the report, which
# otherwise calls a killed leg `completed cleanly: yes` (#118). Deriving it twice is how those
# two would drift apart -- the report would keep printing after someone changed what a wedge
# means here. It stays a string: empty on every other path, so those legs pass an empty
# E2E_WEDGED_AFTER and the report behaves exactly as before.
wedged=""
[ "$status" -eq 124 ] && wedged="$WEDGE_TIMEOUT"

# The run-shape report: expected/received/failed and whether the run finished, every time,
# green or red. It never changes `status` -- it is a diagnostic, and the header's rule about
# diagnostics applies to it as much as to every probe below.
#
# E2E_WEDGED_AFTER is the wedge, told to the report rather than left for it to infer. It cannot
# be inferred: a wedge is gradle never returning, so gradle printed no verdict at all, and the
# log the report reads looks like a run that simply stopped. Only this script knows the
# difference, because only this script saw the exit status.
#
# The baseline argument, and only it, turns on the comparison, and only the advisory API 37 job
# passes E2E_ADVISORY=1. Comparing on the gating legs would announce a deviation on all five of
# them every run, since they run the whole suite rather than the marked three. They still get
# the report: a truncated run reporting fewer results than it ran is what #108 looks like, and
# `completed cleanly` is the field that shows it.
if [ "${E2E_ADVISORY:-}" = "1" ]; then
  E2E_WEDGED_AFTER="$wedged" bash "$SCRIPT_DIR/e2e-report-shape.sh" "$LABEL" "$GRADLE_LOG" \
    "$REPO_ROOT/app/src/androidTest/java/org/libremediaconverter/FailsOnEmulatorApi37.kt" || true
else
  E2E_WEDGED_AFTER="$wedged" bash "$SCRIPT_DIR/e2e-report-shape.sh" "$LABEL" "$GRADLE_LOG" || true
fi

if [ "$status" -eq 0 ]; then
  kill "$LOGCAT_PID" 2>/dev/null || true
  exit 0
fi

if [ -n "$wedged" ]; then
  capture_wedge "api${LABEL}"
else
  echo "::error::E2E api${LABEL} failed (exit $status)"
fi
dump_diagnostics
kill "$LOGCAT_PID" 2>/dev/null || true
exit "$status"
