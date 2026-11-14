#!/usr/bin/env bash
# Builds the release APK, signed with the key in ~/.config/ccez-clock, and
# installs it when a device is named. The key is made once, with
# --new-key, and never again: a new key means the phone can't update and
# must uninstall the app (and its alarms). Without the key, this stops.
#   scripts/build.sh                            build only
#   CLOCK_SERIAL=<adb serial> scripts/build.sh  build + install there
#   scripts/build.sh --new-key                  first time on a new setup only
set -euo pipefail
cd "$(dirname "$0")/.."
KEYDIR="$HOME/.config/ccez-clock"
if [ ! -f "$KEYDIR/release.jks" ]; then
  if [ "${1:-}" != "--new-key" ]; then
    echo "no signing key at $KEYDIR/release.jks: restore it from a backup." >&2
    echo "Only if no release was ever installed anywhere: scripts/build.sh --new-key" >&2
    exit 1
  fi
  mkdir -p "$KEYDIR"
  chmod 700 "$KEYDIR"
  PASS="$(openssl rand -base64 24)"
  printf '%s' "$PASS" > "$KEYDIR/password"
  chmod 600 "$KEYDIR/password"
  keytool -genkeypair -v -keystore "$KEYDIR/release.jks" -alias ccez-clock \
    -keyalg RSA -keysize 4096 -validity 10000 \
    -storepass "$PASS" -keypass "$PASS" -dname "CN=ccez-clock" >/dev/null 2>&1
  echo "made the signing key in $KEYDIR (back it up; never regenerate it)"
fi
chmod 600 "$KEYDIR/release.jks" "$KEYDIR/password"
# The SDK: local.properties, else ANDROID_HOME, else where Android Studio puts it.
if [ ! -f local.properties ] && [ -z "${ANDROID_HOME:-}" ]; then
  export ANDROID_HOME="$HOME/Library/Android/sdk"
fi
export CCEZ_CLOCK_KEYSTORE="$KEYDIR/release.jks"
export CCEZ_CLOCK_KEY_PASSWORD="$(cat "$KEYDIR/password")"
export CCEZ_CLOCK_VERSION_CODE="$(( $(git rev-list --count HEAD) + 20 ))"  # see release.sh
export CCEZ_CLOCK_VERSION_NAME="0.1.$CCEZ_CLOCK_VERSION_CODE"
./gradlew --console=plain -q :app:assembleRelease
APK=app/build/outputs/apk/release/app-release.apk
echo "built $APK ($CCEZ_CLOCK_VERSION_NAME, $(( $(stat -f%z "$APK") / 1024 )) KB)"
SERIAL="${CLOCK_SERIAL:-}"
[ -z "$SERIAL" ] && exit 0
adb -s "$SERIAL" install -r "$APK" >/dev/null || { echo "install failed on $SERIAL" >&2; exit 1; }
echo "installed on $SERIAL"
