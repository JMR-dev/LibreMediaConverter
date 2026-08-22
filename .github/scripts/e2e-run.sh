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

# ~5 min is a healthy leg (measured across API 33-36), and this wraps only the gradle client,
# a subset of that. 20 min is generous enough never to trip on a slow-but-working run, and far
# enough under the job's 60-min cap that a genuine wedge still leaves time to capture it.
WEDGE_TIMEOUT=1200

# Stream logcat from now until the step ends, into a file that survives to the artifact upload.
# Without this, a failure that happens on-device leaves nothing behind: `adb logcat -d` at the
# end only has whatever is still in the ring buffer, and a chatty test run evicts the cause.
echo "===== logcat (api${LABEL}) =====" >> "$LOGCAT_LOG"
adb logcat -v time >> "$LOGCAT_LOG" 2>&1 &
LOGCAT_PID=$!

dump_diagnostics() {
  {
    echo "===== E2E api${LABEL} failure diagnostics -- $(date -u +%FT%TZ) ====="
    echo "--- adb devices ---";                adb devices -l 2>&1 || true
    echo "--- guest memory ---";               adb shell cat /proc/meminfo 2>&1 | grep -E 'MemTotal|MemAvailable|SwapTotal' || true
    echo "--- guest storage ---";              adb shell df /data 2>&1 || true
    echo "--- is the app even installed? ---"; adb shell pm list packages 2>&1 | grep -a libremedia || true
    echo "--- native crashes ---";             adb logcat -d -b crash 2>&1 | tail -80 || true
    echo "--- runner: kvm ---";                ls -l /dev/kvm 2>&1 || true
    echo "--- runner: memory ---";             free -h 2>&1 || true
    echo "--- runner: disk ---";               df -h 2>&1 || true
  } >> "$DIAG_LOG" 2>&1 || true

  # Also to the step log, so the common case needs no artifact download.
  echo "----- FAILURE SUMMARY (api${LABEL}) -----"
  adb shell cat /proc/meminfo 2>&1 | grep -E 'MemTotal|MemAvailable' || true
  echo "--- native crashes (tail 60) ---"
  adb logcat -d -b crash 2>&1 | tail -60 || true
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
    adb shell getprop sys.boot_completed 2>&1 || true
    echo "--- are the binder services published? ---"
    for svc in input window activity media.player; do
      echo "  service check $svc:"; adb shell service check "$svc" 2>&1 || true
    done
    APP_PID="$(adb shell pidof "$APP_ID" 2>/dev/null | tr -d '\r')" || true
    TEST_PID="$(adb shell pidof "$TEST_ID" 2>/dev/null | tr -d '\r')" || true
    echo "--- pids --- app: ${APP_PID:-<none>}  test: ${TEST_PID:-<none>}"
    # SIGQUIT makes ART dump every thread's stack to logcat and /data/anr. This is what
    # distinguishes a deadlocked test from a stuck native encode from a dead device.
    echo "--- SIGQUIT thread dumps ---"
    for pid in $APP_PID $TEST_PID; do
      [ -n "$pid" ] && adb shell kill -3 "$pid" 2>&1 || true
    done
    sleep 5
    echo "--- /data/anr/* ---"
    adb shell 'cat /data/anr/* 2>/dev/null' 2>&1 || true
    echo "--- dumpsys activity ---"; adb shell dumpsys activity 2>&1 || true
    echo "--- dumpsys window ---";   adb shell dumpsys window 2>&1 || true
    # FFmpeg and Media3 both run through MediaCodec; a wedged transcode shows up here.
    echo "--- dumpsys media.player ---"; adb shell dumpsys media.player 2>&1 || true
    echo "--- logcat -d (tail 400, includes the SIGQUIT dump) ---"
    adb logcat -d 2>&1 | tail -400 || true
  } >> "$WEDGE_LOG" 2>&1 || true
  echo "::warning::E2E api${LABEL} WEDGED ($1) -- see the wedge-diagnostics-api${LABEL} artifact"
}

echo "::group::E2E api${LABEL}"
adb shell cat /proc/meminfo 2>&1 | grep -E 'MemTotal|MemAvailable|SwapTotal' || true

status=0
# -k 30s SIGKILLs a gradle client that ignores SIGTERM. The wrapper covers ONLY the foreground
# gradle client -- never the emulator, which the action owns -- so it cannot hang the leg.
timeout -k 30s "$WEDGE_TIMEOUT" \
  ./gradlew :app:connectedDebugAndroidTest -PabiFilters=x86_64 --stacktrace || status=$?
echo "::endgroup::"

if [ "$status" -eq 0 ]; then
  kill "$LOGCAT_PID" 2>/dev/null || true
  exit 0
fi

if [ "$status" -eq 124 ]; then
  capture_wedge "api${LABEL}"
else
  echo "::error::E2E api${LABEL} failed (exit $status)"
fi
dump_diagnostics
kill "$LOGCAT_PID" 2>/dev/null || true
exit "$status"
