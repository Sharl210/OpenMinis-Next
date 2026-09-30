#!/usr/bin/env bash
#
# Run the instrumented (androidTest) suite and report passed / SKIPPED / failed.
#
# Why this exists instead of `./gradlew connectedAndroidTest`:
# the runner's own summary line reports only failures. A test that leaves early
# through `Assume.assumeTrue(...)` is neither a pass nor a failure as far as that
# summary is concerned, so it is not mentioned at all. A suite of 179 cases with
# 49 skipped prints `Tests run: 179, Failures: 4` — which reads like 175 passing.
# It was 130 passing, 4 failing, and 49 that asserted nothing.
#
# So this script never trusts the summary. It reads the per-case status codes out
# of the raw instrumentation stream and counts them itself:
#
#   1  = started
#   0  = passed
#   -2 = failed
#   -4 = assumption violated, i.e. SKIPPED — the case ran no assertions
#
# Skips are listed by name rather than only counted, because "which behaviour is
# not being verified" is the question that actually matters, and a count alone
# answers it only for whoever already knows the suite.
#
# Usage:
#   ./scripts/run-instrumented-tests.sh                     # whole suite
#   ./scripts/run-instrumented-tests.sh -e class com.example.FooTest
#   ./scripts/run-instrumented-tests.sh -e class com.example.FooTest#aMethod
#
# Everything after the first unrecognised argument is passed to `am instrument`
# verbatim, so `-e <key> <value>` and `--no-window-animation` all work.
#
# Exit status:
#   0  every case passed (skips are reported but do not fail the run — see below)
#   1  at least one failure
#   2  the run could not be trusted: no cases were counted, or the stream had no
#      verdicts at all. Never reported as success.
#
# On skips and exit status: a skipped case is not a failure, because some skips
# are correct — `Assume` guards a prerequisite the device genuinely cannot meet
# (this project's sandbox assets are arm64-only, so an x86_64 emulator cannot run
# them). Failing the run for those would train people to ignore the exit code.
# Instead every skip is printed with its reason, so a suite that is mostly
# skipping is loud rather than green. Read the ratio, not just the exit status.

set -uo pipefail

ADB="${ADB:-adb}"
PKG="${ANDROID_APP_ID:-com.openminis.next}"
TEST_PKG="${ANDROID_TEST_ID:-${PKG}.test}"
RUNNER="${ANDROID_TEST_RUNNER:-androidx.test.runner.AndroidJUnitRunner}"
RAW="${ANDROID_TEST_RAW:-}"

if ! command -v "$ADB" >/dev/null 2>&1; then
    echo "error: '$ADB' not found. Set ADB=/path/to/adb." >&2
    exit 2
fi

if [ "$("$ADB" get-state 2>/dev/null)" != "device" ]; then
    echo "error: no device/emulator attached (adb get-state != device)." >&2
    echo "       attached devices:" >&2
    "$ADB" devices | sed 's/^/         /' >&2
    exit 2
fi

if ! "$ADB" shell pm list instrumentation 2>/dev/null | grep -q "${TEST_PKG}/"; then
    echo "error: '${TEST_PKG}' is not installed on the device." >&2
    echo "       build and install both APKs first, e.g." >&2
    echo "         ./gradlew :app:assembleDebug :app:assembleDebugAndroidTest" >&2
    echo "         adb install -r -g app/build/outputs/apk/debug/app-debug.apk" >&2
    echo "         adb install -r -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk" >&2
    exit 2
fi

# Keep the raw stream: it is the only place the skip verdicts exist, and it is
# what a later reader needs in order to check this script's arithmetic.
if [ -z "$RAW" ]; then
    RAW="$(mktemp -t instrumented-XXXXXX.txt)"
fi

echo "==> ${TEST_PKG}/${RUNNER}"
echo "==> raw stream: ${RAW}"
if [ "$#" -gt 0 ]; then
    echo "==> extra args: $*"
fi

started_at=$(date +%s)
"$ADB" shell am instrument -w -r "$@" "${TEST_PKG}/${RUNNER}" >"$RAW" 2>&1
am_status=$?
elapsed=$(( $(date +%s) - started_at ))

# Per-case verdicts. The stream emits a `class=` / `test=` pair followed by one
# or more status codes; the last code for a given pair is its outcome. Counting
# codes instead of parsing the summary line is the entire point of this script.
verdicts=$(
    awk '
        /^INSTRUMENTATION_STATUS: class=/ { sub(/^INSTRUMENTATION_STATUS: class=/, ""); c = $0; next }
        /^INSTRUMENTATION_STATUS: test=/  { sub(/^INSTRUMENTATION_STATUS: test=/,  ""); t = $0; next }
        /^INSTRUMENTATION_STATUS_CODE: /  {
            if (c != "" && t != "") code[c "\t" t] = $2
        }
        END { for (k in code) printf "%s\t%s\n", code[k], k }
    ' "$RAW"
)

total=$(printf '%s\n' "$verdicts" | grep -c .)
passed=$(printf '%s\n' "$verdicts" | awk -F'\t' '$1=="0"' | grep -c .)
failed=$(printf '%s\n' "$verdicts" | awk -F'\t' '$1=="-2"' | grep -c .)
skipped=$(printf '%s\n' "$verdicts" | awk -F'\t' '$1=="-4"' | grep -c .)
other=$(printf '%s\n' "$verdicts" | awk -F'\t' '$1!="0" && $1!="-2" && $1!="-4"' | grep -c .)

if [ "$total" -eq 0 ]; then
    echo >&2
    echo "FAIL: no per-case verdicts were found in the stream." >&2
    echo "      The run cannot be reported as passing. Last lines of ${RAW}:" >&2
    tail -20 "$RAW" | sed 's/^/      /' >&2
    exit 2
fi

echo
if [ "$failed" -gt 0 ]; then
    echo "failures:"
    printf '%s\n' "$verdicts" | awk -F'\t' '$1=="-2" { printf "  FAIL  %s :: %s\n", $2, $3 }'
    echo
fi

if [ "$skipped" -gt 0 ]; then
    echo "skipped (Assume guard — these asserted nothing):"
    printf '%s\n' "$verdicts" | awk -F'\t' '$1=="-4" { printf "  SKIP  %s :: %s\n", $2, $3 }'
    echo
    echo "  reasons:"
    grep -oE 'AssumptionViolatedException: .*' "$RAW" | sed 's/^/    /' | sort | uniq -c | sort -rn | head -5
    echo
fi

echo "----------------------------------------------------------------"
printf 'passed %s / skipped %s / failed %s   (total %s, %ss)\n' \
    "$passed" "$skipped" "$failed" "$total" "$elapsed"

if [ "$other" -gt 0 ]; then
    printf 'warning: %s case(s) ended with an unexpected status code:\n' "$other"
    printf '%s\n' "$verdicts" | awk -F'\t' '$1!="0" && $1!="-2" && $1!="-4" { printf "  %s  %s :: %s\n", $1, $2, $3 }'
fi

if [ "$skipped" -gt 0 ]; then
    pct=$(( skipped * 100 / total ))
    printf 'note: %s%% of cases verified nothing. Quote the skipped count whenever you quote this run.\n' "$pct"
fi

if [ "$failed" -gt 0 ]; then
    echo "----------------------------------------------------------------"
    echo "raw stream kept at: ${RAW}"
    exit 1
fi

if [ "$am_status" -ne 0 ] && [ "$passed" -eq 0 ]; then
    echo "----------------------------------------------------------------"
    echo "FAIL: am instrument exited ${am_status} with no passing cases." >&2
    echo "      raw stream: ${RAW}" >&2
    exit 2
fi

echo "----------------------------------------------------------------"
echo "raw stream kept at: ${RAW}"
exit 0
