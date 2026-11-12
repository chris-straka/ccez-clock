#!/usr/bin/env bash
# Builds the release APK, signed with the key in ~/.config/ccez-clock (made
# once here, never regenerated: a new key means uninstalling the app and
# its alarms), and installs it when a device is named.
#   scripts/build.sh                            build only
#   CLOCK_SERIAL=<adb serial> scripts/build.sh  build + install there
set -euo pipefail
cd "$(dirname "$0")/.."
KEYDIR="$HOME/.config/ccez-clock"
mkdir -p "$KEYDIR"
chmod 700 "$KEYDIR"
if [ ! -f "$KEYDIR/release.jks" ]; then
  PASS="$(openssl rand -base64 24)"
  printf '%s' "$PASS" > "$KEYDIR/password"
  chmod 600 "$KEYDIR/password"
  keytool -genkeypair -v -keystore "$KEYDIR/release.jks" -alias ccez-clock \
    -keyalg RSA -keysize 4096 -validity 10000 \
    -storepass "$PASS" -keypass "$PASS" -dname "CN=ccez-clock" >/dev/null 2>&1
  echo "made the signing key in $KEYDIR (back it up; never regenerate it)"
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
