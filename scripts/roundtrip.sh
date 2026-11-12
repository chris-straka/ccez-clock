#!/usr/bin/env bash
# The agent path end to end, on an emulator against the real clock server:
#  1. an alarm made on the phone's screen shows up in `clock list` (the CLI
#     over the tailnet);
#  2. an alarm added with `clock add +2` reaches the phone through its
#     periodic sync job and rings over the off screen;
#  3. dismissing it there (a one-off turns off) shows up in `clock list`.
# It clears the app's data first and deletes its alarms from the server
# after, so nothing of it reaches the owner's phone.
#   CLOCK_SERIAL=emulator-5564 scripts/roundtrip.sh
set -euo pipefail
cd "$(dirname "$0")/.."
SERIAL="${CLOCK_SERIAL:?set CLOCK_SERIAL to an emulator}"
case "$SERIAL" in emulator-*) ;; *) echo "roundtrip.sh runs on emulators only, not $SERIAL" >&2; exit 1 ;; esac
export CLOCK_SERIAL="$SERIAL" PATH="$HOME/.local/share/mise/shims:$PATH"
OUT="${1:-$HOME/SWE/.muse-runs/clock-shots}"
A() { adb -s "$SERIAL" "$@"; }
clock() { bun scripts/clock.ts "$@"; }
# The emulator reaches z's server through its host alias (its DNS can't
# resolve the tailnet name reliably); the CLI uses the tailnet address.
RUN="$(date +%H%M%S)"
EMU_SERVER="${EMU_SERVER:-http://10.0.2.2:8799}"
JOB=7101
tap() {
  A shell uiautomator dump /sdcard/ui.xml >/dev/null
  local b
  b="$(A shell cat /sdcard/ui.xml | tr '>' '\n' | grep "resource-id=\"$1\"" | head -1 | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/')"
  [ -n "$b" ] || { echo "no $1 on screen" >&2; return 1; }
  set -- $b
  A shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}
ids=()
cleanup() { for id in ${ids[@]+"${ids[@]}"}; do clock delete "$id" >/dev/null 2>&1 || true; done; }
trap cleanup EXIT

A shell pm clear uk.ccez.clock >/dev/null
A shell settings put global hide_error_dialogs 1
clock setup >/dev/null
clock set sync.server "$EMU_SERVER" >/dev/null
clock set sync.device clock_avd >/dev/null
# A sync to the old address may still hold the lock: one through to the new one first.
clock sync >/dev/null
A shell input keyevent KEYCODE_WAKEUP; A shell wm dismiss-keyguard 2>/dev/null || true

echo "1. phone → CLI"
A shell am start -n uk.ccez.clock/.MainActivity --es tab alarm >/dev/null; sleep 2
tap add; sleep 1; tap label; A shell input text "PhoneMade$RUN"; A shell input keyevent KEYCODE_BACK; tap save
id=""
for _ in $(seq 1 30); do
  id="$(clock list --json | python3 -c 'import json,sys;print(next((a["id"] for a in json.load(sys.stdin) if a["label"]=="PhoneMade'$RUN'"), ""))')"
  [ -n "$id" ] && break
  sleep 1
done
[ -n "$id" ] || { echo "the phone's alarm never reached the server" >&2; exit 1; }
ids+=("$id")
clock list | grep PhoneMade$RUN

echo "2. CLI → phone, rings"
line="$(clock add +2 --label FromTheMac$RUN)"
echo "$line"
id2="${line%% *}"; ids+=("$id2")
A shell cmd jobscheduler run -f uk.ccez.clock $JOB >/dev/null; sleep 5
clock list --adb | grep -q FromTheMac$RUN || { echo "the phone didn't get it" >&2; exit 1; }
A shell input keyevent KEYCODE_HOME
A shell input keyevent KEYCODE_SLEEP; sleep 2
for _ in $(seq 1 90); do
  A shell dumpsys activity activities | grep -q "topResumedActivity.*RingActivity" && break
  sleep 2
done
A shell dumpsys activity activities | grep -q "topResumedActivity.*RingActivity" || { echo "it never rang" >&2; exit 1; }
sleep 2
A shell dumpsys power | grep -q "mWakefulness=Awake" || { echo "rang but the screen stayed off" >&2; exit 1; }
A exec-out screencap -p > "$OUT/ring-roundtrip.png"
echo "rang at $(A shell date +%T) with the screen on"

echo "3. dismiss on the phone → CLI"
tap dismiss
off=""
for _ in $(seq 1 30); do
  clock list --json | python3 -c 'import json,sys;a=next(a for a in json.load(sys.stdin) if a["label"]=="FromTheMac'$RUN'");sys.exit(0 if not a["enabled"] else 1)' && { off=1; break; }
  sleep 1
done
[ -n "$off" ] || { echo "the CLI doesn't see it off" >&2; exit 1; }
clock list | grep FromTheMac$RUN
clock phone
echo "round trip ok"
