#!/usr/bin/env bash
# Every alarm sound as WAV (two loops each), rendered by Synth.kt on the
# JVM, for listening before they ship.
#   scripts/sounds.sh [out dir]
set -euo pipefail
cd "$(dirname "$0")/.."
OUT="$(cd "$(dirname "${1:-screenshots/sounds}")" 2>/dev/null && pwd)/$(basename "${1:-screenshots/sounds}")"
mkdir -p "$OUT"
CLOCK_SOUNDS_OUT="$OUT" ./gradlew --console=plain -q :app:testDebugUnitTest --tests uk.ccez.clock.SoundFiles --rerun
ls -la "$OUT"
