#!/usr/bin/env bash
#
# Reports the SHAPE of an instrumented run -- how many tests were expected, how many
# reported, how many failed, and whether the run completed at all -- to the step log and to
# the job summary. In advisory mode it also compares that shape against a committed baseline
# and says plainly whether it matches.
#
# WHY THIS EXISTS (#83): the advisory API 37 leg is red on every PR by design, so a NEW failure
# joining the known ones is invisible -- nothing in a red X distinguishes "the known ones" from
# "the known ones plus yours". CLAUDE.md tells everyone not to read that job's red as their
# change breaking something, which is correct, and which also means nobody looks.
#
# WHY NOT A BARE FAILURE COUNT, measured rather than assumed. On this image the run is usually
# truncated: `Test run failed to complete. Expected 3 tests, received 2.` with
# `INSTRUMENTATION_ABORTED: System has crashed.` A count taken from a truncated run misleads in
# both directions -- a fourth marked test can still yield the same number if the abort lands
# earlier, and the known set getting worse can LOWER it. So all four fields are recorded, and
# the one saying the run was truncated is recorded with them.
#
# WHY IT IS A SEPARATE SCRIPT rather than a function inside e2e-run.sh: it is a pure seam. It
# reads a captured log plus the test XML and writes a report, so it can be run against a REAL
# log saved from a REAL CI run -- which is how the baseline comparison was shown to fire
# without waiting on an emulator. `git ls-files '*.sh'` also picks it up for shellcheck for
# free.
#
# THIS SCRIPT NEVER FAILS A RUN. It is a diagnostic, and e2e-run.sh's header explains why that
# rule is absolute here. Every field defaults to `unknown` and every comparison is guarded,
# because an unset variable under `set -u`, or a `[ "" -eq 3 ]`, is exactly how a diagnostic
# becomes the thing that turns a leg red. It exits 0 unconditionally.
#
# Usage:
#   e2e-report-shape.sh <label> <gradle-log> [<baseline-file>]
#
# With a third argument the run is compared against the baseline in that file (advisory mode)
# and a `::notice::` is emitted per deviation. NEVER `::error::`: the advisory job is
# `continue-on-error: true` and stays that way, and an error annotation would be a new way for
# a diagnostic to change a conclusion.
set -uo pipefail

LABEL="${1:-unknown}"
LOG="${2:-}"
BASELINE_FILE="${3:-}"

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/../.." && pwd)"
XML_DIR="$REPO_ROOT/app/build/outputs/androidTest-results/connected/debug"

# Gradle colours its output even when it is piped, so `FAILED` arrives wrapped in escape codes.
# The numeric lines parsed below are not coloured, but stripping is cheap insurance against a
# pattern that would otherwise silently match nothing.
ESC="$(printf '\033')"
scan() { [ -s "$LOG" ] && sed -e "s/${ESC}\[[0-9;]*[a-zA-Z]//g" -- "$LOG"; }
first_number() { grep -oE '[0-9]+' | head -1; }

# ---------------------------------------------------------------------------
# Source 1: the runner's own output. This is the ONLY place a truncation is visible. The test
# XML read below is written even for an aborted run and says nothing whatever about the abort
# -- measured on run 32865281555, where the XML reports a tidy tests="3" failures="3" for a run
# the runner had just described as truncated. That is the reason this parses stdout at all.
# ---------------------------------------------------------------------------
starting_line="$(scan | grep -aoE 'Starting [0-9]+ tests on .*' | tail -1)"
abort_line="$(scan | grep -aoE 'Test run failed to complete\. Expected [0-9]+ tests, received [0-9]+\.' | tail -1)"
aborted_hits="$(scan | grep -ac 'INSTRUMENTATION_ABORTED' || true)"
failure_line="$(scan | grep -aoE 'There was [0-9]+ failure\(s\)\.' | tail -1)"
failed_names="$(scan | grep -aoE 'Execute [A-Za-z0-9_.$]+: FAILED' | sed -e 's/^Execute //' -e 's/: FAILED$//' | sort -u)"

expected="$(printf '%s' "$starting_line" | first_number)"
expected_src="\`$starting_line\`"
abort_expected="$(printf '%s' "$abort_line" | grep -oE 'Expected [0-9]+' | first_number)"
abort_received="$(printf '%s' "$abort_line" | grep -oE 'received [0-9]+' | first_number)"
log_failed="$(printf '%s' "$failure_line" | first_number)"

# `Starting N tests` is missing when the framework restarted under the run and Gradle never got
# a test list. The truncation line still carries the number it was told to expect.
if [ -z "$expected" ] && [ -n "$abort_expected" ]; then
  expected="$abort_expected"
  expected_src="\`$abort_line\`"
fi

# ---------------------------------------------------------------------------
# Source 2: the JUnit XML. Measured on both a truncated advisory run and a green gating leg:
# `<testsuites tests="N" failures="M">` is present in both, and aggregates every suite. It is
# the authority on how many results landed and how many were failures. It is NOT an authority
# on whether the run finished, which is what source 1 is for.
# ---------------------------------------------------------------------------
#
# Read ONLY when the runner said a test run happened. `app/build` survives between runs on a
# developer machine -- tools/local-emulator/run-e2e.sh drives several API levels against one
# checkout -- so a leg that never got as far as starting tests would otherwise be reported from
# the previous leg's XML, which is a wrong answer rather than a missing one.
xml_head=""
xml_count=0
if [ -n "$starting_line$abort_line" ] && [ -d "$XML_DIR" ]; then
  while IFS= read -r f; do
    xml_count=$((xml_count + 1))
    [ -z "$xml_head" ] && xml_head="$(grep -ao '<testsuites[^>]*>' "$f" | head -1)"
  done < <(find "$XML_DIR" -maxdepth 1 -name 'TEST-*.xml' -print 2> /dev/null | sort)
fi
xml_tests="$(printf '%s' "$xml_head" | grep -oE ' tests="[0-9]+"' | first_number)"
xml_failed="$(printf '%s' "$xml_head" | grep -oE ' failures="[0-9]+"' | first_number)"

# ---------------------------------------------------------------------------
# Derive the four fields, each with where its number came from. Everything stays a string, so a
# missing source reads `unknown` rather than becoming 0 -- a report claiming 0 tests when it
# merely could not see them would announce a deviation on every cancelled run.
# ---------------------------------------------------------------------------
received="unknown"
received_src="no source"
if [ -n "$xml_tests" ]; then
  received="$xml_tests"
  received_src="test XML \`<testsuites tests=\"$xml_tests\">\`"
elif [ -n "$abort_received" ]; then
  received="$abort_received"
  received_src="\`$abort_line\`"
elif [ -n "$expected" ] && [ -z "$abort_line" ]; then
  received="$expected"
  received_src="the run was not truncated, so every expected test reported"
fi

failed="unknown"
failed_src="no source"
if [ -n "$xml_failed" ]; then
  failed="$xml_failed"
  failed_src="test XML \`<testsuites failures=\"$xml_failed\">\`"
elif [ -n "$log_failed" ]; then
  failed="$log_failed"
  failed_src="\`$failure_line\`"
fi

if [ -z "$expected" ]; then
  expected="unknown"
  expected_src="no \`Starting N tests\` line"
fi

# A run whose start nobody can see is not a run of zero tests. Cancellation (this workflow sets
# cancel-in-progress) and the `Starting 0 tests` shape a framework restart produces both land
# here, and both have to say so rather than compare a number that does not exist.
no_run="none"
if [ "$expected" = "unknown" ] && [ "$received" = "unknown" ]; then
  no_run="nothing"
elif [ "$expected" = "0" ]; then
  no_run="zero"
fi

if [ -n "$abort_line" ]; then
  completed="**no**"
  completed_src="\`$abort_line\` with \`INSTRUMENTATION_ABORTED\`"
elif [ "${aborted_hits:-0}" -gt 0 ]; then
  completed="**no**"
  completed_src="\`INSTRUMENTATION_ABORTED\` in the runner output"
elif [ "$no_run" = "nothing" ]; then
  # "cleanly" would be a lie about a run that left no evidence it happened.
  completed="unknown"
  completed_src="no runner output to read"
else
  completed="yes"
  completed_src="no truncation line and no \`INSTRUMENTATION_ABORTED\`"
fi

# ---------------------------------------------------------------------------
# Advisory mode: compare against the committed baseline.
#
# ONE number covers both compared fields, and that is deliberate rather than a shortcut. The
# marker means "cannot pass on this image", so the number of tests carrying it is both how many
# the advisory leg should run and how many should fail. A smaller `failed` means one now passes
# -- which is the trigger to delete the annotation, written down in FailsOnEmulatorApi37.kt.
# ---------------------------------------------------------------------------
baseline=""
marked=""
deviations=()
if [ -n "$BASELINE_FILE" ] && [ -f "$BASELINE_FILE" ]; then
  baseline="$(sed -nE 's/^const val FAILS_ON_EMULATOR_API37_BASELINE = ([0-9]+).*/\1/p' "$BASELINE_FILE" | head -1)"
  # The #81 check, verbatim: what the tree actually carries. Reported next to the baseline so a
  # stale baseline shows up here rather than only once the emulator disagrees with it.
  if [ -d "$REPO_ROOT/app/src/androidTest" ]; then
    marked="$(grep -rn "@FailsOnEmulatorApi37" "$REPO_ROOT/app/src/androidTest" --include='*.kt' \
      | grep -v import | grep -c FailsOn || true)"
  fi
fi

if [ -n "$baseline" ]; then
  if [ "$no_run" = "nothing" ]; then
    deviations+=("no test run observed — the runner never reported starting one, where the baseline expects $baseline tests carrying \`@FailsOnEmulatorApi37\`")
  elif [ "$no_run" = "zero" ]; then
    deviations+=("the runner started 0 tests, where the baseline expects $baseline — on this image that is the framework having restarted under the run, not an empty test list")
  else
    if [ "$expected" != "unknown" ] && [ "$expected" != "$baseline" ]; then
      deviations+=("the runner started $expected tests, the baseline is $baseline")
    fi
    if [ "$failed" != "unknown" ] && [ "$failed" != "$baseline" ]; then
      deviations+=("$failed tests failed, the baseline is $baseline — every test carrying the marker is expected to fail on this image, so fewer means one now passes and more means a new one joined")
    fi
  fi
  if [ -n "$marked" ] && [ "$marked" != "$baseline" ]; then
    deviations+=("the tree carries $marked tests marked \`@FailsOnEmulatorApi37\` but the baseline says $baseline — update FAILS_ON_EMULATOR_API37_BASELINE")
  fi
fi

# ---------------------------------------------------------------------------
# Emit. Step log first, so the common case needs neither the summary page nor an artifact.
# ---------------------------------------------------------------------------
echo "----- RUN SHAPE (api${LABEL}) -----"
echo "  expected:          $expected"
echo "  received:          $received"
echo "  failed:            $failed"
echo "  completed cleanly: ${completed//\*/}"
if [ -n "$abort_received" ]; then
  echo "  received before the abort: $abort_received"
fi
if [ -n "$failed_names" ]; then
  echo "  failed tests:"
  printf '%s\n' "$failed_names" | sed -e 's/^/    /'
fi
if [ -n "$baseline" ]; then
  if [ "${#deviations[@]}" -eq 0 ]; then
    echo "  baseline: matches ($baseline expected, $baseline failed)"
  else
    printf '  baseline DEVIATION: %s\n' "${deviations[@]}"
  fi
fi

# A notice, never an error. See the header.
if [ "${#deviations[@]}" -gt 0 ]; then
  for d in "${deviations[@]}"; do
    echo "::notice::E2E api${LABEL}: $d"
  done
fi

if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  {
    echo "### E2E api${LABEL} — run shape"
    echo
    echo "| field | value | where it came from |"
    echo "| --- | --- | --- |"
    echo "| expected | $expected | $expected_src |"
    echo "| received | $received | $received_src |"
    echo "| failed | $failed | $failed_src |"
    echo "| completed cleanly | $completed | $completed_src |"
    if [ -n "$abort_received" ]; then
      echo "| received before the abort | $abort_received | the same line — the XML above counts the truncated test as a failure, this number does not |"
    fi
    echo
    if [ -n "$failed_names" ]; then
      echo "Failed:"
      echo
      printf '%s\n' "$failed_names" | sed -e 's/^/- `/' -e 's/$/`/'
      echo
    fi
    if [ "$xml_count" -gt 1 ]; then
      echo "> $xml_count test XML files were present; the counts above come from the first."
      echo
    fi
    if [ -n "$baseline" ]; then
      if [ "${#deviations[@]}" -eq 0 ]; then
        echo "**Matches the committed baseline of $baseline** — $baseline tests carry \`@FailsOnEmulatorApi37\` and all $baseline failed, which is what this job is for."
      else
        echo "**DEVIATION from the committed baseline of $baseline.** Announced as a notice, not an error: this job is advisory and its conclusion is unchanged by anything here."
        echo
        printf -- '- %s\n' "${deviations[@]}"
      fi
      echo
      echo "<sub>The baseline lives beside the marker, in \`FailsOnEmulatorApi37.kt\`. \`completed cleanly\` is recorded rather than compared: the truncation is intermittent — of eight advisory runs read on 2026-08-25, seven aborted and one did not — so comparing it would announce a deviation on a run that is fine.</sub>"
    else
      echo "<sub>No baseline comparison: that is the advisory API 37 leg only. The shape is recorded here anyway because a truncated run reports fewer results than it ran, which is what issue #108 looks like on a gating leg.</sub>"
    fi
    echo
  } >> "$GITHUB_STEP_SUMMARY"
fi

exit 0
