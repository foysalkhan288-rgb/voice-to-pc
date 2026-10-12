#!/usr/bin/env bash
set -euo pipefail

package_name="com.ashiqe.voicetopc"
apk_path="app/build/outputs/apk/debug/app-debug.apk"
log_path="app/build/outputs/apk/debug/emulator-smoke-test.log"
mkdir -p "$(dirname "$log_path")"

save_logcat() {
  adb logcat -d -b crash -b main -b system -v threadtime >"$log_path" 2>/dev/null || true
}
trap save_logcat EXIT

adb install -r "$apk_path"
adb shell pm grant "$package_name" android.permission.CAMERA
adb shell am force-stop "$package_name"
adb logcat -c
adb shell am start -W -n "$package_name/.MainActivity"
sleep 3

if ! adb shell pidof "$package_name" >/dev/null 2>&1; then
  echo "The app process exited after launch." >&2
  exit 1
fi

adb shell uiautomator dump /sdcard/voice-to-pc-window.xml >/dev/null
adb pull /sdcard/voice-to-pc-window.xml "$RUNNER_TEMP/voice-to-pc-window.xml" >/dev/null
read -r tap_x tap_y < <(python3 - "$RUNNER_TEMP/voice-to-pc-window.xml" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET

root = ET.parse(sys.argv[1]).getroot()
for node in root.iter("node"):
    if node.attrib.get("text") == "Pair with PC":
        match = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.attrib["bounds"])
        if match:
            left, top, right, bottom = map(int, match.groups())
            print((left + right) // 2, (top + bottom) // 2)
            break
else:
    raise SystemExit("Pair with PC button was not visible in the app UI.")
PY
)

adb shell input tap "$tap_x" "$tap_y"
sleep 4
if ! adb shell dumpsys activity activities | grep -Fq "com.journeyapps.barcodescanner.CaptureActivity"; then
  echo "Tapping Pair with PC did not open the QR scanner activity." >&2
  adb shell dumpsys activity activities | grep -E "mResumedActivity|topResumedActivity" >&2 || true
  exit 1
fi

adb shell input keyevent 4
sleep 2
if ! adb shell pidof "$package_name" >/dev/null 2>&1; then
  echo "The app process exited after returning from the QR scanner." >&2
  exit 1
fi

echo "PASS: app launched, Pair with PC opened the QR scanner, and the app survived returning from it."
