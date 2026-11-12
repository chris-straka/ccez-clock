#!/usr/bin/env bash
# The quality gates, on an emulator only (it uninstalls and reinstalls the
# app, which would lose the alarms on a phone): server tests, unit tests,
# Compose UI and alarm-fires tests, then screenshots.
#   CLOCK_SERIAL=emulator-5564 scripts/check.sh
set -euo pipefail
cd "$(dirname "$0")/.."
SERIAL="${CLOCK_SERIAL:?set CLOCK_SERIAL to an emulator}"
case "$SERIAL" in emulator-*) ;; *) echo "check.sh runs on emulators only, not $SERIAL" >&2; exit 1 ;; esac
export ANDROID_SERIAL="$SERIAL"
(cd server && cargo test -q)
./gradlew --console=plain -q :app:testDebugUnitTest
# The no-audio emulator's media stack stalls system processes: their dialogs would cover the screens.
adb -s "$SERIAL" shell settings put global hide_error_dialogs 1
# A release build signs differently: out first, or the test install fails.
adb -s "$SERIAL" uninstall uk.ccez.clock >/dev/null 2>&1 || true
./gradlew --console=plain -q :app:connectedDebugAndroidTest
python3 - <<'PY'
import glob, re, sys
s = open(glob.glob("app/build/outputs/androidTest-results/connected/debug/*.xml")[0]).read()
n, f = map(int, re.search(r'tests="(\d+)" failures="(\d+)"', s).groups())
print(f"instrumented tests: {n}, failures: {f}")
sys.exit(0 if n > 0 and f == 0 else 1)
PY
CLOCK_SERIAL="$SERIAL" scripts/build.sh
CLOCK_SERIAL="$SERIAL" scripts/shots.sh
echo "all gates passed"
