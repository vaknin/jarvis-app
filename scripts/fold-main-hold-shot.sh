#!/usr/bin/env bash
# The phone main chat runs on the thread's reply box, as the emulator really draws it: builds and installs the debug
# app on emulator-5554 (booting the jarvis-emu AVD headless if it isn't up), opens the fold trial deck ending on
# Jarvis's lead paragraph and three bullets, then
#   1. swipes the second bullet right and fails unless the replying strip holds that bullet alone (fold-main-swipe.png);
#   2. types hold_me into the reply, taps Hold, and fails unless the strip is gone and the marks tray counts one held
#      reply (fold-main-held.png);
#   3. types later_please, long-presses Send, and fails unless later_please shows in the chat with the box empty
#      (fold-main-held-plain.png).
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
"${adb[@]}" shell am start -W -n "$app/com.kivan.jarvis.tabs.TabsActivity" --ei tabs 6 --ez fold true --ez bullets true >/dev/null

dump() {
    "${adb[@]}" shell uiautomator dump /sdcard/ui.xml >/dev/null
    "${adb[@]}" exec-out cat /sdcard/ui.xml >"$tmp/ui.xml"
}
fail() {
    cp "$tmp/ui.xml" "$out/fold-main-ui.xml"
    "${adb[@]}" exec-out screencap -p >"$out/fold-main-fail.png" || true
    echo "$1 (dump in $out/fold-main-ui.xml)" >&2
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

# 1. The trial deck's bullets (Fake's bulletTalk): the finger goes on the second.
lead="Two ways to make it calmer:"
want="lower, two steps down"
others=("slower, at nine tenths" "both together, softer" "$lead")
bounds=""
for _ in $(seq 20); do
    dump
    bounds=$(grep -o "text=\"$want\"[^>]*bounds=\"[^\"]*\"" "$tmp/ui.xml" | grep -o 'bounds="[^"]*"' | head -1 || true)
    [ -n "$bounds" ] && break
    sleep 1
done
[ -n "$bounds" ] || fail "the main chat never showed: $want"
read -r x1 y1 x2 y2 <<<"$(echo "$bounds" | grep -o '[0-9]\+' | tr '\n' ' ')"
x=$((x1 + 20))
y=$(((y1 + y2) / 2))
# Right, past the reply line (SWIPE), in 300 ms: well under Android's 400 ms long-press time once moving.
"${adb[@]}" shell input swipe "$x" "$y" $((x + 400)) "$y" 300
sleep 1
dump
# The strip's text: the last words drawn before its Cancel this reply button, in the same row.
strip=$(tr '>' '\n' <"$tmp/ui.xml" | sed -n '/content-desc="Cancel this reply"/q; s/.* text="\([^"]\+\)".*/\1/p' | tail -1 || true)
[ "$strip" = "$want" ] || fail "the strip holds \"$strip\", not \"$want\""
for o in "${others[@]}"; do
    case "$strip" in *"$o"*) fail "the strip also holds \"$o\"" ;; esac
done
"${adb[@]}" exec-out screencap -p >"$out/fold-main-swipe.png"
echo "swipe quotes: $strip"

# 2. A reply held with the Hold pill: the strip goes, the tray counts it held, nothing is sent.
# The swipe asks the box for focus; key presses sent before the keyboard is up can land twice.
for _ in $(seq 10); do
    "${adb[@]}" shell dumpsys input_method | grep -q 'mInputShown=true' && break
    sleep 1
done
sleep 1
"${adb[@]}" shell input text hold_me
sleep 1
dump
at=$(centre Hold)
[ -n "$at" ] || fail "no Hold pill on the open reply"
# shellcheck disable=SC2086
"${adb[@]}" shell input tap $at
sleep 1
dump
grep -q 'content-desc="Cancel this reply"' "$tmp/ui.xml" && fail "the reply is still open after Hold"
grep -q 'text="↩ 1"' "$tmp/ui.xml" || fail "the marks tray doesn't count one held reply"
grep -q 'text="hold_me"' "$tmp/ui.xml" || fail "the held reply's words aren't in the tray"
"${adb[@]}" exec-out screencap -p >"$out/fold-main-held.png"
echo "held: hold_me on \"$want\""

# 3. Plain words held with a long press on Send: in the chat, the box empty.
at=$(centre "Talk to Jarvis")
[ -n "$at" ] || fail "no empty main chat box"
# shellcheck disable=SC2086
"${adb[@]}" shell input tap $at
sleep 1
"${adb[@]}" shell input text later_please
sleep 1
dump
[ "$(boxed)" = later_please ] || fail "the box holds \"$(boxed)\", not later_please"
at=$(centre Send)
[ -n "$at" ] || fail "no Send button"
# shellcheck disable=SC2086
set -- $at
# A press that stays put for 900 ms: past Android's 400 ms long-press time.
"${adb[@]}" shell input swipe "$1" "$2" "$1" "$2" 900
sleep 1
dump
[ "$(boxed)" != later_please ] || fail "later_please is still in the box"
tr '>' '\n' <"$tmp/ui.xml" | grep 'text="later_please"' | grep -qv 'android.widget.EditText' || fail "later_please isn't in the chat"
"${adb[@]}" exec-out screencap -p >"$out/fold-main-held-plain.png"
echo "held plain: later_please, box \"$(boxed)\""
echo "$out/fold-main-swipe.png $out/fold-main-held.png $out/fold-main-held-plain.png"
