#!/usr/bin/env bash
# Screenshots of each tab, the editor, settings and the ring screen, at the
# normal size and at the largest font and display size. Emulator only: it
# seeds alarms (sync turned off first, so nothing reaches the server).
#   CLOCK_SERIAL=emulator-5564 scripts/shots.sh [out dir]
# Reviewed copies go in screenshots/.
set -euo pipefail
cd "$(dirname "$0")/.."
SERIAL="${CLOCK_SERIAL:?set CLOCK_SERIAL to an emulator}"
case "$SERIAL" in emulator-*) ;; *) echo "shots.sh runs on emulators only, not $SERIAL" >&2; exit 1 ;; esac
OUT="${1:-$HOME/SWE/.muse-runs/clock-shots}"
mkdir -p "$OUT"
export CLOCK_SERIAL="$SERIAL" PATH="$HOME/.local/share/mise/shims:$PATH"
A() { adb -s "$SERIAL" "$@"; }
clock() { bun scripts/clock.ts "$@"; }
shot() { sleep 1.5; A exec-out screencap -p > "$OUT/$1.png"; echo "$1"; }
# Taps the centre of the node with this test tag (Compose tags are resource ids here).
tap() {
  A shell uiautomator dump /sdcard/ui.xml >/dev/null
  local b
  b="$(A shell cat /sdcard/ui.xml | tr '>' '\n' | grep "resource-id=\"$1\"" | head -1 | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/')"
  [ -n "$b" ] || { echo "no $1 on screen" >&2; return 1; }
  set -- $b
  A shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}
open() { A shell am start -n uk.ccez.clock/.MainActivity --es tab "$1" >/dev/null; sleep 1; }

A shell settings put global hide_error_dialogs 1
# What the phone gets too: no permission prompt over the screens.
clock setup >/dev/null
A shell am force-stop uk.ccez.clock
A shell input keyevent KEYCODE_WAKEUP
A shell wm dismiss-keyguard 2>/dev/null || true
clock set sync.server '""' >/dev/null
for id in $(clock list --adb | python3 -c 'import json,sys;[print(a["id"]) for a in json.load(sys.stdin)]'); do clock delete "$id" --adb >/dev/null; done
clock timer cancel --adb >/dev/null
clock add 06:45 weekdays --label Gym --adb >/dev/null
clock add 08:30 weekends --adb >/dev/null
clock add 07:15 mon,wed,fri --label "French class" --sound dawn --adb >/dev/null
clock add 22:30 once --label "Bins out" --off --adb >/dev/null

series() {
  local s="$1"
  A shell am force-stop uk.ccez.clock; open alarm
  shot "alarm$s"
  tap add; shot "editor$s"; A shell input keyevent KEYCODE_BACK
  tap settings; shot "settings$s"; A shell input keyevent KEYCODE_BACK
  open stopwatch; tap start; sleep 2.2; tap lap; sleep 1.3; tap lap; sleep 0.8; shot "stopwatch$s"; tap stop; tap reset
  open timer; shot "timer$s"
  clock timer 25m --adb >/dev/null; sleep 4; shot "timer-running$s"; clock timer cancel --adb >/dev/null
  local id
  id="$(clock list --adb | python3 -c 'import json,sys;print([a for a in json.load(sys.stdin) if a["label"]=="Gym"][0]["id"])')"
  A shell input keyevent KEYCODE_SLEEP; sleep 2
  clock ring "$id" >/dev/null; sleep 4; shot "ring$s"
  clock dismiss >/dev/null; sleep 1
  # The dismissed one-off is not this (Gym repeats); nothing else to undo.
  A shell input keyevent KEYCODE_WAKEUP; A shell wm dismiss-keyguard 2>/dev/null || true
}

series ""
# The largest font and display size the phone offers.
A shell settings put system font_scale 2.0
A shell wm density 560
sleep 2
series "-large"
A shell settings put system font_scale 1.0
A shell wm density reset
A shell am force-stop uk.ccez.clock
echo "shots in $OUT"
