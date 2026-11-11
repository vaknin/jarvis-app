#!/usr/bin/env bash
# His screenshot sent from the phone, as the emulator really draws the main chat: builds and installs the debug app
# on emulator-5554 (booting the jarvis-emu AVD headless if it isn't up), opens the fold trial deck ending on a
# picture he sent under the server's name, fails unless the accessibility dump shows that picture wholly on screen
# and above the text box, then saves the screen as android/app/build/shots/fold-sent-picture-emu.png.
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
apk="$root/android/app/build/outputs/apk/debug/app-debug.apk"
# An older-numbered build than the one installed is refused as a downgrade: start clean then.
if ! "${adb[@]}" install -r -t "$apk" >"$tmp/install.log" 2>&1; then
    grep -q DOWNGRADE "$tmp/install.log" || { cat "$tmp/install.log" >&2; exit 1; }
    "${adb[@]}" uninstall com.kivan.jarvis.debug >/dev/null
    "${adb[@]}" install -r -t "$apk" >/dev/null
fi
# TabsActivity isn't exported: starting it by hand takes root, which the emulator gives.
"${adb[@]}" root >/dev/null
"${adb[@]}" wait-for-device
"${adb[@]}" shell input keyevent KEYCODE_WAKEUP
"${adb[@]}" shell wm dismiss-keyguard >/dev/null 2>&1 || true
"${adb[@]}" shell am force-stop com.kivan.jarvis.debug
"${adb[@]}" shell am start -W -n com.kivan.jarvis.debug/com.kivan.jarvis.tabs.TabsActivity --ei tabs 6 --ez fold true --ez picture true >/dev/null

dump() {
    "${adb[@]}" shell uiautomator dump /sdcard/ui.xml >/dev/null
    "${adb[@]}" exec-out cat /sdcard/ui.xml >"$tmp/ui.xml"
}

name="1794117294387-0.png"
pic=""
for _ in $(seq 20); do
    dump
    pic=$(grep -o "content-desc=\"$name\"[^>]*bounds=\"[^\"]*\"" "$tmp/ui.xml" | grep -o 'bounds="[^"]*"' | head -1 || true)
    [ -n "$pic" ] && break
    sleep 1
done
fail() {
    cp "$tmp/ui.xml" "$out/fold-sent-picture-ui.xml"
    echo "$1 (dump in $out/fold-sent-picture-ui.xml)" >&2
    exit 1
}
[ -n "$pic" ] || fail "his picture $name never showed"
read -r x1 y1 x2 y2 <<<"$(echo "$pic" | grep -o '[0-9]\+' | tr '\n' ' ')"
read -r w h <<<"$("${adb[@]}" shell wm size | grep -o '[0-9]\+x[0-9]\+' | tail -1 | tr 'x' ' ')"
[ "$x1" -ge 0 ] && [ "$y1" -ge 0 ] && [ "$x2" -le "$w" ] && [ "$y2" -le "$h" ] || fail "his picture $pic is not wholly on the ${w}x${h} screen"
# The text box: the editable field, whose top the picture must sit above.
box=$(grep -o 'class="android.widget.EditText"[^>]*bounds="[^"]*"' "$tmp/ui.xml" | grep -o 'bounds="[^"]*"' | head -1 || true)
[ -n "$box" ] || fail "no text box on screen"
read -r _ by1 _ _ <<<"$(echo "$box" | grep -o '[0-9]\+' | tr '\n' ' ')"
[ "$y2" -le "$by1" ] || fail "his picture $pic runs under the text box $box"
"${adb[@]}" exec-out screencap -p >"$out/fold-sent-picture-emu.png"
echo "his picture $pic, text box $box: $out/fold-sent-picture-emu.png"
