#!/usr/bin/env bash
#
# Exercises e2e-report-shape.sh's baseline counter against fixture source, with no emulator and
# no CI run. Run it directly:
#
#   .github/scripts/e2e-report-shape-test.sh
#
# WHY THIS CAN EXIST AT ALL: the counter is a pure function of the working tree. It greps
# `app/src/androidTest` for `@FailsOnEmulatorApi37` and compares the total against the number
# committed in FailsOnEmulatorApi37.kt. Nothing about that needs a device, which is the whole
# reason #120 could be measured rather than argued about.
#
# WHY A THROWAWAY REPO ROOT rather than a knob on the script. The report finds its own root from
# `BASH_SOURCE`, so a copy of it placed at `<root>/.github/scripts/` reads `<root>/app/src/...`.
# Building that root is three mkdirs and costs the shipped script nothing:
#
#   - the REAL script is what runs, byte for byte, so reverting the matcher reddens this test
#     rather than a testing-only code path beside it;
#   - no environment variable exists that could point the LIVE count somewhere else, which is
#     the failure mode #83 built the baseline check to prevent in the first place;
#   - XML_DIR resolves inside the throwaway root, so a stale app/build/outputs left by a real
#     run on a developer machine cannot leak into the numbers here.
#
# WHAT IT GUARDS (#120). The old matcher looked for the string anywhere on any line, so a KDoc
# saying `Deliberately not @FailsOnEmulatorApi37` counted as a marked test and every PR got a
# deviation notice that was wrong. The obvious repair -- count only lines that are nothing but
# the annotation -- silently stops counting `@FailsOnEmulatorApi37 @Test`, which is legal Kotlin,
# and undercounting is the direction that hides a genuine new marker. The fixture carries every
# shape at once -- three that count and three that must not, enumerated in its own header -- so
# both mistakes fail here instead of on a PR: against testdata/marker-shapes the old matcher says
# 5, own-line-only says 2, and the shipped one 3.
#
# NOT WIRED INTO CI, deliberately and as a known gap. Adding a step to the Static analysis job
# would add a new way for a gating job to go red, and #120 was explicit that nothing about it may
# change any job's status or the pass/fail rules. shellcheck still covers this file, since that
# step reads `git ls-files '*.sh'` rather than a fixed list.
set -uo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPORT="$SCRIPT_DIR/e2e-report-shape.sh"
FIXTURE_DIR="$SCRIPT_DIR/testdata/marker-shapes"
FIXTURE="$FIXTURE_DIR/MarkerShapes.kt"

TMP="$(mktemp -d)"
# Single quotes: the path is expanded when the trap fires, not when it is set.
trap 'rm -rf -- "$TMP"' EXIT

failures=0

pass() { printf 'ok    %s\n' "$1"; }

fail() {
  failures=$((failures + 1))
  printf 'FAIL  %s\n' "$1"
  shift
  printf '        %s\n' "$@"
}

# assert_contains <name> <haystack> <needle>
# `case` rather than grep: the strings being matched carry backticks and an em dash, and this
# way neither the shell nor a regex engine gets an opinion about them.
assert_contains() {
  case "$2" in
    *"$3"*) pass "$1" ;;
    *) fail "$1" "wanted to find: $3" "in:" "$2" ;;
  esac
}

assert_absent() {
  case "$2" in
    *"$3"*) fail "$1" "did NOT want to find: $3" "in:" "$2" ;;
    *) pass "$1" ;;
  esac
}

# make_root <marked-tree-dir> <baseline-number>
# Assembles a throwaway repo root around the given tree and prints its path.
make_root() {
  local tree="$1" baseline="$2" root testdir
  root="$(mktemp -d "$TMP/root.XXXXXX")"
  testdir="$root/app/src/androidTest/java/org/libremediaconverter"
  mkdir -p "$root/.github/scripts" "$testdir"
  cp -- "$REPORT" "$root/.github/scripts/"
  cp -- "$tree"/*.kt "$testdir/"

  # The synthetic stand-in for the committed baseline. Its KDoc names the marker the way the real
  # file does -- in brackets, never with an `@` -- because the real file lives inside the tree
  # being counted, so an `@` spelling here would add a phantom to every number below.
  cat > "$testdir/FailsOnEmulatorApi37.kt" <<EOF
package org.libremediaconverter

/** Stand-in for the real marker file. Only [FAILS_ON_EMULATOR_API37_BASELINE] is read. */
const val FAILS_ON_EMULATOR_API37_BASELINE = $baseline
EOF

  # A clean, untruncated run of exactly <baseline> tests, all failing -- which is what the
  # advisory leg looks like when nothing has drifted. It leaves the marked count as the only
  # field that can deviate, so every assertion below is about the thing under test.
  cat > "$root/gradle.log" <<EOF
> Task :app:connectedDebugAndroidTest
Starting $baseline tests on test(AVD) - 16
There was $baseline failure(s).
EOF

  printf '%s\n' "$root"
}

# run_report <root>  -- stdout of the real script; its summary lands in <root>/summary.md.
run_report() {
  E2E_WEDGED_AFTER='' GITHUB_STEP_SUMMARY="$1/summary.md" \
    bash "$1/.github/scripts/e2e-report-shape.sh" 37 "$1/gradle.log" \
    "$1/app/src/androidTest/java/org/libremediaconverter/FailsOnEmulatorApi37.kt"
}

# ---------------------------------------------------------------------------
# The fixture still carries every shape.
#
# Three of the checks below are covered twice over -- deleting a real annotation moves the count
# and fails a case further down. The two decoys are not: drop the KDoc mention and the count
# stays 3, so the precision this whole ticket is about would stop being tested and nothing would
# say so. That asymmetry is why the shapes are asserted by name rather than only by their effect
# on the total.
# ---------------------------------------------------------------------------
fixture_text="$(cat -- "$FIXTURE")"
assert_contains "fixture: the import"           "$fixture_text" 'import org.libremediaconverter.FailsOnEmulatorApi37'
assert_contains "fixture: annotation own line"  "$fixture_text" '
    @FailsOnEmulatorApi37
    @Test'
assert_contains "fixture: annotation with @Test on one line" "$fixture_text" '@FailsOnEmulatorApi37 @Test'
assert_contains "fixture: annotation nested and indented" "$fixture_text" '
        @FailsOnEmulatorApi37'
assert_contains "fixture: KDoc mention (this is #120)" "$fixture_text" "* Deliberately not \`@FailsOnEmulatorApi37\`"
assert_contains "fixture: commented-out annotation" "$fixture_text" '// @FailsOnEmulatorApi37'

# ---------------------------------------------------------------------------
# 1. The fixture's three real annotations against a baseline of 3: no deviation.
#
# This one case fails under both wrong matchers -- the old one counts 5, own-line-only counts 2 --
# which is why it is first.
# ---------------------------------------------------------------------------
root="$(make_root "$FIXTURE_DIR" 3)"
out="$(run_report "$root")"
assert_contains "3 real markers, baseline 3: reports a match" "$out" '  baseline: matches (3 expected, 3 failed)'
assert_absent   "3 real markers, baseline 3: says nothing about the tree" "$out" 'the tree carries'
assert_contains "3 real markers, baseline 3: summary agrees" \
  "$(cat -- "$root/summary.md")" '**Matches the committed baseline of 3**'

# ---------------------------------------------------------------------------
# 2. A fourth REAL annotation. The count has to move and the deviation has to fire.
#
# The important half of #120: precision was the bug, but a matcher that stopped noticing a new
# marker would have been a worse one, silently.
# ---------------------------------------------------------------------------
plus_one="$(mktemp -d "$TMP/plusone.XXXXXX")"
cp -- "$FIXTURE" "$plus_one/"
cat > "$plus_one/FourthMarker.kt" <<'EOF'
package org.libremediaconverter.fixture

class FourthMarker {
    @FailsOnEmulatorApi37
    @Test
    fun addedToday() = Unit
}
EOF
root="$(make_root "$plus_one" 3)"
out="$(run_report "$root")"
assert_contains "a 4th real marker: the deviation fires, and counts 4" "$out" \
  "  baseline DEVIATION: the tree carries 4 tests marked \`@FailsOnEmulatorApi37\` but the baseline says 3 — update FAILS_ON_EMULATOR_API37_BASELINE"
assert_contains "a 4th real marker: the summary carries it too" "$(cat -- "$root/summary.md")" \
  "- the tree carries 4 tests marked \`@FailsOnEmulatorApi37\` but the baseline says 3"

# ---------------------------------------------------------------------------
# 3. Delete the same-line annotation and the count must drop to 2.
#
# This is the trap, pinned down. `@FailsOnEmulatorApi37 @Test` on one line is what separates the
# shipped matcher from `^[[:space:]]*@NAME[[:space:]]*$`, and without this case the fixture entry
# guarding it could be deleted as decoration -- case 1 would then pass under the wrong matcher.
# Here the same-line entry is worth exactly one, and it is asserted to be.
# ---------------------------------------------------------------------------
minus_same_line="$(mktemp -d "$TMP/minus.XXXXXX")"
sed -e '/@FailsOnEmulatorApi37 @Test/d' -- "$FIXTURE" > "$minus_same_line/MarkerShapes.kt"
root="$(make_root "$minus_same_line" 3)"
out="$(run_report "$root")"
assert_contains "same-line annotation removed: counts 2, so it was worth 1" "$out" \
  "  baseline DEVIATION: the tree carries 2 tests marked \`@FailsOnEmulatorApi37\` but the baseline says 3 — update FAILS_ON_EMULATOR_API37_BASELINE"

echo
if [ "$failures" -eq 0 ]; then
  echo "e2e-report-shape-test.sh: all checks passed"
  exit 0
fi
echo "e2e-report-shape-test.sh: $failures check(s) failed"
exit 1
