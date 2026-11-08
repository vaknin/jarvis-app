#!/usr/bin/env bash
# The phone main chat's hold menu, as the emulator really draws it (Robolectric never draws a popup):
# builds and installs the debug app on emulator-5554 (booting the jarvis-emu AVD headless if it isn't up),
# opens the fold trial deck, long-presses Jarvis's last message, fails unless the screen's accessibility dump
# shows Quote part, Copy and Open thread, then saves the screen as android/app/build/shots/fold-hold-menu.png.
# Only ever the emulator: never a real phone.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
export ANDROID_HOME=${ANDROID_HOME:-$HOME/Android/Sdk}
sdk=$ANDROID_HOME
# rustup's cargo: mise's pinned toolchain has no Android target.
export PATH="$HOME/.cargo/bin:$sdk/platform-tools:$sdk/emulator:$PATH"
dev=emulator-5554
adb=(adb -s "$dev")
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
"${adb[@]}" install -r -t "$root/android/app/build/outputs/apk/debug/app-debug.apk" >/dev/null
# TabsActivity isn't exported: starting it by hand takes root, which the emulator gives.
"${adb[@]}" root >/dev/null
"${adb[@]}" wait-for-device
"${adb[@]}" shell input keyevent KEYCODE_WAKEUP
"${adb[@]}" shell wm dismiss-keyguard >/dev/null 2>&1 || true
"${adb[@]}" shell am force-stop com.kivan.jarvis.debug
"${adb[@]}" shell am start -W -n com.kivan.jarvis.debug/com.kivan.jarvis.tabs.TabsActivity --ei tabs 6 --ez fold true >/dev/null

dump() {
    "${adb[@]}" shell uiautomator dump /sdcard/ui.xml >/dev/null
    "${adb[@]}" exec-out cat /sdcard/ui.xml >"$tmp/ui.xml"
}

# The message, by its words on screen: its centre is where the finger goes.
msg="A calmer voice, slower and lower. Trying two."
bounds=""
for _ in $(seq 20); do
    dump
    bounds=$(grep -o "text=\"$msg\"[^>]*bounds=\"[^\"]*\"" "$tmp/ui.xml" | grep -o 'bounds="[^"]*"' | head -1 || true)
    [ -n "$bounds" ] && break
    sleep 1
done
[ -n "$bounds" ] || { echo "the main chat never showed: $msg" >&2; exit 1; }
read -r x1 y1 x2 y2 <<<"$(echo "$bounds" | grep -o '[0-9]\+' | tr '\n' ' ')"
x=$(((x1 + x2) / 2))
y=$(((y1 + y2) / 2))
# A swipe that doesn't move is a long press: held a second, past Android's 400 ms long-press time.
"${adb[@]}" shell input swipe "$x" "$y" "$x" "$y" 1000
sleep 1

dump
missing=""
for item in "Quote part" "Copy" "Open thread"; do
    grep -q "text=\"$item\"" "$tmp/ui.xml" || missing="$missing \"$item\""
done
if [ -n "$missing" ]; then
    cp "$tmp/ui.xml" "$out/fold-hold-menu-ui.xml"
    echo "the hold menu is missing$missing (dump in $out/fold-hold-menu-ui.xml)" >&2
    exit 1
fi
"${adb[@]}" exec-out screencap -p >"$out/fold-hold-menu.png"
echo "$out/fold-hold-menu.png"
