#!/usr/bin/env bash
# Send keeps the view where he reads, as the emulator really draws the main chat: builds and installs the debug app
# on emulator-5554 (booting the jarvis-emu AVD headless if it isn't up), opens the fold trial deck, taps the box,
# scrolls up by finger, notes the topmost line on screen, sends, and fails unless that line keeps its place and his
# message stays out of view. Saves send-stays-emu-before.png and send-stays-emu.png in android/app/build/shots.
# Only ever the emulator: never a real phone.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk}
sdk=$ANDROID_HOME
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
    grep -q DOWNGRADE "$tmp/install.log" || { cat "$tmp/install.log" >&2; exit 1; }
    "${adb[@]}" uninstall "$app" >/dev/null
    "${adb[@]}" install -r -t "$apk" >/dev/null
fi
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
    cp "$tmp/ui.xml" "$out/send-stays-ui.xml"
    "${adb[@]}" exec-out screencap -p >"$out/send-stays-fail.png" || true
    echo "$1 (dump in $out/send-stays-ui.xml, screen in $out/send-stays-fail.png)" >&2
    exit 1
}
centre() {
    local b
    b=$(tr '>' '\n' <"$tmp/ui.xml" | grep -E "(text|content-desc)=\"$1\"" | grep -o 'bounds="[^"]*"' | head -1 || true)
    [ -n "$b" ] || return 0
    read -r x1 y1 x2 y2 <<<"$(echo "$b" | grep -o '[0-9]\+' | tr '\n' ' ')"
    echo "$(((x1 + x2) / 2)) $(((y1 + y2) / 2))"
}
# Every non-box text node as "top|text", top to bottom.
lines() {
    tr '>' '\n' <"$tmp/ui.xml" | grep ' text="[^"]\+' | grep -v 'android.widget.EditText' |
        sed -n 's/.* text="\([^"]*\)".*bounds="\[[0-9]*,\([0-9]*\)\].*/\2|\1/p' | sort -n
}

at=""
for _ in $(seq 20); do
    dump
    at=$(centre "Talk to Jarvis")
    [ -n "$at" ] && break
    sleep 1
done
[ -n "$at" ] || fail "the main chat never showed its box"
# shellcheck disable=SC2086
"${adb[@]}" shell input tap $at
for _ in $(seq 10); do
    "${adb[@]}" shell dumpsys input_method | grep -q 'mInputShown=true' && break
    sleep 1
done
sleep 1
read -r w h <<<"$("${adb[@]}" shell wm size | grep -o '[0-9]\+x[0-9]\+' | tail -1 | tr 'x' ' ')"
# Up by his finger: older lines come down into view.
"${adb[@]}" shell input swipe $((w / 2)) $((h / 4)) $((w / 2)) $((h / 2)) 300
sleep 2
dump
# The first line below the header (an eighth of the screen down): the message he is reading.
first=$(lines | awk -F'|' -v min=$((h / 8)) '$1 > min' | head -1)
[ -n "$first" ] || fail "no lines on screen after scrolling up"
words=${first#*|}
top=${first%%|*}
"${adb[@]}" exec-out screencap -p >"$out/send-stays-emu-before.png"
"${adb[@]}" shell input text stay_put_please
sleep 1
dump
at=$(centre Send)
[ -n "$at" ] || fail "no Send button"
# shellcheck disable=SC2086
"${adb[@]}" shell input tap $at
sleep 2
dump
"${adb[@]}" exec-out screencap -p >"$out/send-stays-emu.png"
now=$(lines | grep -F "|$words" | head -1 | cut -d'|' -f1)
[ -n "$now" ] || fail "\"$words\" left the screen after Send"
[ "$now" = "$top" ] || fail "\"$words\" moved from $top to $now after Send"
lines | grep -q '|stay_put_please$' && fail "the view was pulled down to his message"
echo "stays: \"$words\" at $top before and after Send"
echo "$out/send-stays-emu-before.png $out/send-stays-emu.png"
