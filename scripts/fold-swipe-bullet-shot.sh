#!/usr/bin/env bash
# The phone main chat's swipe quotes one bullet, not the whole message, as the emulator really draws it: builds and
# installs the debug app on emulator-5554 (booting the jarvis-emu AVD headless if it isn't up), opens the fold trial
# deck ending on Jarvis's lead paragraph and three bullets, swipes the second bullet right, fails unless the
# accessibility dump's replying strip holds that bullet and none of the others nor the lead, then saves the screen
# as android/app/build/shots/fold-swipe-bullet.png.
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
"${adb[@]}" shell am start -W -n com.kivan.jarvis.debug/com.kivan.jarvis.tabs.TabsActivity --ei tabs 6 --ez fold true --ez bullets true >/dev/null

dump() {
    "${adb[@]}" shell uiautomator dump /sdcard/ui.xml >/dev/null
    "${adb[@]}" exec-out cat /sdcard/ui.xml >"$tmp/ui.xml"
}

# The trial deck's bullets (Fake's bulletTalk): the finger goes on the second.
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
[ -n "$bounds" ] || { echo "the main chat never showed: $want" >&2; exit 1; }
read -r x1 y1 x2 y2 <<<"$(echo "$bounds" | grep -o '[0-9]\+' | tr '\n' ' ')"
x=$((x1 + 20))
y=$(((y1 + y2) / 2))
# Right, past the reply line (SWIPE), in 300 ms: well under Android's 400 ms long-press time once moving.
"${adb[@]}" shell input swipe "$x" "$y" $((x + 400)) "$y" 300
sleep 1

dump
# The strip's text: the last words drawn before its Cancel this reply button, in the same row.
strip=$(tr '>' '\n' <"$tmp/ui.xml" | sed -n '/content-desc="Cancel this reply"/q; s/.* text="\([^"]\+\)".*/\1/p' | tail -1 || true)
bad=""
[ "$strip" = "$want" ] || bad="the strip holds \"$strip\", not \"$want\""
for o in "${others[@]}"; do
    case "$strip" in *"$o"*) bad="$bad; the strip also holds \"$o\"" ;; esac
done
if [ -n "$bad" ]; then
    cp "$tmp/ui.xml" "$out/fold-swipe-bullet-ui.xml"
    echo "${bad#; } (dump in $out/fold-swipe-bullet-ui.xml)" >&2
    exit 1
fi
"${adb[@]}" exec-out screencap -p >"$out/fold-swipe-bullet.png"
echo "strip: $strip"
echo "$out/fold-swipe-bullet.png"
