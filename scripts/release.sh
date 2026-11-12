#!/usr/bin/env bash
# A release on GitHub (private repo, built locally: no Actions): the
# signed APK and its SHA-256 (the app finds it there and installs it
# itself when updates.self is on, Updater.kt). Gates first: server and
# unit tests always; run scripts/check.sh on an emulator before this.
#   scripts/release.sh
set -euo pipefail
cd "$(dirname "$0")/.."
[ -z "$(git status --porcelain)" ] || { echo "commit first" >&2; exit 1; }
(cd server && cargo test -q)
./gradlew --console=plain -q :app:testDebugUnitTest
scripts/build.sh
# The public history starts over (2026-11-12) after 0.1.17 shipped from a
# private one: count on from there, so every version stays higher.
CODE="$(( $(git rev-list --count HEAD) + 20 ))"
TAG="v0.1.$CODE"
APK="app/build/outputs/apk/release/app-release.apk"
OUT="$(mktemp -d)"
cp "$APK" "$OUT/ccez-clock-$TAG.apk"
(cd "$OUT" && shasum -a 256 "ccez-clock-$TAG.apk" > "ccez-clock-$TAG.apk.sha256")
SIZE="$(( $(stat -f%z "$APK") / 1024 )) KB"
if gh release view "$TAG" >/dev/null 2>&1; then echo "$TAG is released: commit something new first" >&2; exit 1; fi
git rev-parse -q --verify "refs/tags/$TAG" >/dev/null || git tag "$TAG"
git push -q origin "$TAG"
gh release create "$TAG" "$OUT/ccez-clock-$TAG.apk" "$OUT/ccez-clock-$TAG.apk.sha256" \
  --title "ccez-clock $TAG" --notes "$(git log -1 --format=%s) (APK $SIZE)" --latest
echo "released $TAG ($SIZE)"
