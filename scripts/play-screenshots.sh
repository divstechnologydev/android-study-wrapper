#!/bin/bash
# Google Play / AppGallery listing screenshots, captured from a connected
# device or emulator (the Android twin of iOS Scripts/app-store-screenshots.sh).
#
# Play wants 2–8 phone shots, PNG/JPEG, 16:9 or 9:16, each side 320–3840 px,
# and ≥1080 px on each side to be promotion-eligible. The emulator's native
# 1080x2400 is 9:20 and gets refused, so the script resizes the display to
# 1080x1920 for the capture and restores it afterwards.
#
# Captures the demo flow (docs/plan.md §a5), which needs no invitation, no
# study code and no network, using the debug MOVEO_AUTO_* intent extras.
#
# Usage:  scripts/play-screenshots.sh [output-dir]

set -euo pipefail

OUT="${1:-docs/store/screenshots}"
PKG="one.moveo.studywrapper"
ACTIVITY="$PKG/.MainActivity"
WIDTH=1080
HEIGHT=1920
DENSITY=420

# Screen name -> the intent extras that put the app on that screen. A demo
# run persists, so each shot starts from `pm clear` and the entry screen.
SHOTS=(
  "01-entry|"
  "02-how-it-works|-e MOVEO_AUTO_SHEET howitworks"
  "03-demo-setup|-e MOVEO_AUTO_SHEET demo"
  "04-consent|-e MOVEO_AUTO_CODE MOVEODEMO"
  "05-study-home|-e MOVEO_AUTO_CODE MOVEODEMO -e MOVEO_AUTO_FLOW enroll"
  "06-browser|-e MOVEO_AUTO_CODE MOVEODEMO -e MOVEO_AUTO_FLOW browser"
  "07-results|-e MOVEO_AUTO_SHEET results"
)

if ! adb get-state >/dev/null 2>&1; then
  echo "No device/emulator. Start one (emulator -avd <name> -read-only) first." >&2
  exit 1
fi

mkdir -p "$OUT"

echo "==> building + installing the debug build (the demo flow is identical"
echo "    in release; debug is what carries the MOVEO_* capture hooks)"
./gradlew :app:installPlayDebug -q

restore() {
  adb shell wm size reset >/dev/null 2>&1 || true
  adb shell wm density reset >/dev/null 2>&1 || true
}
trap restore EXIT

echo "==> display -> ${WIDTH}x${HEIGHT} (9:16)"
adb shell wm size "${WIDTH}x${HEIGHT}" >/dev/null
adb shell wm density "$DENSITY" >/dev/null
adb shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
# Resizing restarts SystemUI, which on a cold emulator can ANR; let it settle
# before the first shot or the dialog lands in the picture.
sleep 20

# Nothing but the app may be on screen when the shutter fires: a system
# dialog ("System UI isn't responding") would be captured with it.
wait_for_app_window() {
  for _ in $(seq 1 15); do
    adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS >/dev/null 2>&1 || true
    if adb shell dumpsys window 2>/dev/null | grep -q "mCurrentFocus=.*$PKG/"; then
      return 0
    fi
    sleep 2
  done
  echo "    !! app window never took focus — check the shot" >&2
}

# Study sites greet a fresh profile with a cookie banner, and `pm clear`
# makes every run a fresh profile. Dismiss it by button text so the browser
# shot shows the page rather than the consent mat; unknown banners are left
# alone (the shot is still honest, just busier).
dismiss_cookie_banner() {
  local dump
  dump=$(adb exec-out uiautomator dump /dev/tty 2>/dev/null || true)
  [ -z "$dump" ] && return 0
  python3 - "$dump" <<'EOF' | while read -r x y; do adb shell input tap "$x" "$y" >/dev/null; done
import re, sys
dump = sys.argv[1]
wanted = ("reject all", "reject", "decline", "only necessary", "accept all")
best = None
for node in re.finditer(r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', dump):
    label = node.group(1).strip().lower()
    if label in wanted:
        rank = wanted.index(label)
        if best is None or rank < best[0]:
            x1, y1, x2, y2 = (int(node.group(i)) for i in range(2, 6))
            best = (rank, (x1 + x2) // 2, (y1 + y2) // 2)
if best:
    print(best[1], best[2])
EOF
  sleep 2
}

for shot in "${SHOTS[@]}"; do
  label="${shot%%|*}"
  extras="${shot##*|}"
  [ "$extras" = "$label" ] && extras=""
  echo "    $label"
  adb shell pm clear "$PKG" >/dev/null
  # shellcheck disable=SC2086
  adb shell am start -n "$ACTIVITY" $extras >/dev/null
  # The browser shot waits on a real page load; the rest just need layout.
  if [ "$label" = "06-browser" ]; then sleep 14; else sleep 7; fi
  wait_for_app_window
  [ "$label" = "06-browser" ] && dismiss_cookie_banner
  adb exec-out screencap -p > "$OUT/$label.png"
done

echo
echo "Done -> $OUT"
echo "Verify before uploading (Play refuses anything but 16:9 / 9:16):"
echo "  sips -g pixelWidth -g pixelHeight $OUT/*.png"
