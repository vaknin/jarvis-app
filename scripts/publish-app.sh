#!/bin/sh
# Offers the finished release build to the phone: Tether updates itself from Jarvis's server through
# the forum tunnel (android/.../Updater.kt), from anywhere, without a tap (the first time after an adb
# install, one). Publishing during a call is fine: the phone installs in a pause and the call goes on. Run on the server after
# `./gradlew :app:assembleRelease` on a committed master. The notes are the "For you:" lines of the
# commits since the build published last (versionCode is the commit count, so that's the newest N).
# The publishing itself is the jarvis repo's `forum-gate publish` (forum/phone/tunnel/src/builds.rs).
set -eu
cd "$(dirname "$0")/.."
apk=android/app/build/outputs/apk/release/app-release.apk
code=$(jq -r '.elements[0].versionCode' "${apk%/*}/output-metadata.json")
head=$(git rev-list --count HEAD)
if [ "$code" != "$head" ]; then
    echo "the APK is versionCode $code but HEAD is commit $head: rebuild first" >&2
    exit 1
fi
# Only master's commits: its count only grows (it's never rewritten), so every publish is newer.
git fetch -q origin master
if ! git merge-base --is-ancestor HEAD origin/master; then
    echo "HEAD isn't on origin/master: land it first, then build and publish from there" >&2
    exit 1
fi
# Signed with Jarvis's release key, or the phone refuses it after the download.
sdk=${ANDROID_HOME:-$HOME/Android/Sdk}
signer=$(ls -d "$sdk"/build-tools/*/apksigner | tail -n1)
if ! "$signer" verify --print-certs "$apk" | grep -qE "^(V[0-9.]+ )?Signer( #1)?:? certificate DN: "; then
    echo "the APK isn't signed: is ~/.config/jarvis/tether-keystore.properties there?" >&2
    exit 1
fi
if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
    echo "uncommitted changes: commit them, rebuild, then publish (the APK must be HEAD)" >&2
    exit 1
fi
latest=${BUILDS_DIR:-$HOME/.local/share/jarvis/builds}/com.kivan.tether/latest.json
old=$(jq -r '.version_code // 0' "$latest" 2>/dev/null || echo 0)
new=$((code - old))
[ "$old" -eq 0 ] || [ "$new" -gt 20 ] && new=20
notes=$(git log -n "$new" --format=%B | grep '^For you:' || true)
exec "${FORUM_GATE:-$HOME/.local/bin/forum-gate}" publish "$apk" "$notes"
