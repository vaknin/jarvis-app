#!/usr/bin/env bash
# Hold is there with nothing quoted, as the emulator really draws it: builds and installs the debug app on
# emulator-5554 (booting the jarvis-emu AVD headless if it isn't up), opens the fold main chat of the trial deck, then
#   1. types hold_this_one with no reply open and fails unless a Hold pill shows and no replying strip (hold-pill.png);
#   2. taps Hold and fails unless hold_this_one shows in the chat and the box is empty (hold-held.png).
# Shots land in android/app/build/shots. Only ever the emulator: never a real phone.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk}
sdk=$ANDROID_HOME
# rustup's cargo: mise's pinned toolchain has no Android target.
export PATH="$HOME/.cargo/bin:$sdk/platform-tools:$sdk/emulator:$PATH"
dev=emulator-5554
adb=(adb -s "$dev")
app=com.kivan.jarvis.debug
out="$root/android/app/build/shots"
mkdir -p "$out"
tmp=$(mktemp -d)
started=""
cleanup() {
    rm -rf "$tmp"
    if [ -n "$started" ]; then "${adb[@]}" emu kill >/dev/null 2>&1 || true; fi
}
trap cleanup EXIT

if ! adb devices | grep -q "^$dev[[:space:]]*device"; then
    emulator -avd jarvis-emu -no-window -no-audio -no-boot-anim -no-snapshot-save -port 5554 >"$tmp/emulator.log" 2>&1 &
    started=1
fi
"${adb[@]}" wait-for-device
for _ in $(seq 180); do
    [ "$("${adb[@]}" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && break
    sleep 1
done
[ "$("${adb[@]}" shell getprop sys.boot_completed | tr -d '\r')" = 1 ] || { echo "the emulator didn't boot" >&2; exit 1; }

(cd "$root/android" && ./gradlew -q :app:assembleDebug)
apk="$root/android/app/build/outputs/apk/debug/app-debug.apk"
if ! "${adb[@]}" install -r -t "$apk" >"$tmp/install.log" 2>&1; then
    # A newer build already there (another branch's): the emulator's copy goes, the trial deck needs nothing of it.
    grep -q DOWNGRADE "$tmp/install.log" || { cat "$tmp/install.log" >&2; exit 1; }
    "${adb[@]}" uninstall "$app" >/dev/null
    "${adb[@]}" install -r -t "$apk" >/dev/null
fi
# TabsActivity isn't exported: starting it by hand takes root, which the emulator gives.
"${adb[@]}" root >/dev/null
"${adb[@]}" wait-for-device
"${adb[@]}" shell input keyevent KEYCODE_WAKEUP
"${adb[@]}" shell wm dismiss-keyguard >/dev/null 2>&1 || true
"${adb[@]}" shell am force-stop "$app"
"${adb[@]}" shell am start -W -n "$app/com.kivan.jarvis.tabs.TabsActivity" --ei tabs 6 --ez fold true >/dev/null

dump() {
    "${adb[@]}" shell uiautomator dump /sdcard/ui.xml >/dev/null
    "${adb[@]}" exec-out cat /sdcard/ui.xml >"$tmp/ui.xml"
}
fail() {
    cp "$tmp/ui.xml" "$out/hold-ui.xml"
    "${adb[@]}" exec-out screencap -p >"$out/hold-fail.png" || true
    echo "$1 (dump in $out/hold-ui.xml)" >&2
    exit 1
}
# The centre of the first node whose text or content-desc is exactly $1, as "x y"; empty if none.
centre() {
    local b
    b=$(tr '>' '\n' <"$tmp/ui.xml" | grep -E "(text|content-desc)=\"$1\"" | grep -o 'bounds="[^"]*"' | head -1 || true)
    [ -n "$b" ] || return 0
    read -r x1 y1 x2 y2 <<<"$(echo "$b" | grep -o '[0-9]\+' | tr '\n' ' ')"
    echo "$(((x1 + x2) / 2)) $(((y1 + y2) / 2))"
}
# The words in the box: the EditText's text (Compose puts the hint there when it's empty).
boxed() { tr '>' '\n' <"$tmp/ui.xml" | grep 'class="android.widget.EditText"' | sed -n 's/.* text="\([^"]*\)".*/\1/p' | head -1; }

# 1. Plain words, nothing quoted: the Hold pill stands beside Send.
at=""
for _ in $(seq 20); do
    dump
    at=$(centre "Talk to Jarvis")
    [ -n "$at" ] && break
    sleep 1
done
[ -n "$at" ] || fail "no empty main chat box"
# shellcheck disable=SC2086
"${adb[@]}" shell input tap $at
# Key presses sent before the keyboard is up can land twice.
for _ in $(seq 10); do
    "${adb[@]}" shell dumpsys input_method | grep -q 'mInputShown=true' && break
    sleep 1
done
sleep 1
"${adb[@]}" shell input text hold_this_one
sleep 1
dump
[ "$(boxed)" = hold_this_one ] || fail "the box holds \"$(boxed)\", not hold_this_one"
grep -q 'resource-id="replying"' "$tmp/ui.xml" && fail "a reply is open: this check is for nothing quoted"
grep -q 'content-desc="Cancel this reply"' "$tmp/ui.xml" && fail "a reply is open: this check is for nothing quoted"
at=$(centre Hold)
[ -n "$at" ] || fail "no Hold pill with nothing quoted"
"${adb[@]}" exec-out screencap -p >"$out/hold-pill.png"
echo "Hold pill at $at, nothing quoted"

# 2. Hold: his words wait, shown in the chat, and the box empties.
# shellcheck disable=SC2086
"${adb[@]}" shell input tap $at
sleep 1
dump
[ "$(boxed)" != hold_this_one ] || fail "hold_this_one is still in the box"
tr '>' '\n' <"$tmp/ui.xml" | grep 'text="hold_this_one"' | grep -qv 'android.widget.EditText' || fail "hold_this_one isn't in the chat"
[ -z "$(centre Hold)" ] || fail "the Hold pill stays on an empty box"
"${adb[@]}" exec-out screencap -p >"$out/hold-held.png"
echo "held: hold_this_one, box \"$(boxed)\""
echo "$out/hold-pill.png $out/hold-held.png"
