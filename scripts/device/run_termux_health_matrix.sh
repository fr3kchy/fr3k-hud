#!/usr/bin/env bash
# Real-phone status regression. Preserves original granted permission and app data.
set -euo pipefail
ADB=(adb -s "${FR3K_PHONE_SERIAL:-f4c8d828}")
OUT="${1:-${FR3K_EVIDENCE_DIR:-evidence/android-pass-2026-09-09-usb}}"
mkdir -p "$OUT"
"${ADB[@]}" shell dumpsys package com.mcpintelligence.fr3k.hud | rg 'com.termux.permission.RUN_COMMAND: granted=true' >/dev/null || {
  echo 'Precondition: RUN_COMMAND must already be granted; refusing to change original permission state.' >&2
  exit 1
}
restore() {
  "${ADB[@]}" shell pm grant com.mcpintelligence.fr3k.hud com.termux.permission.RUN_COMMAND
  "${ADB[@]}" shell am start -n com.mcpintelligence.fr3k.hud/com.mcpintelligence.fr3k.ui.MainActivity >/dev/null
}
trap restore EXIT
run_connected() {
  "${ADB[@]}" shell am instrument -w -r -e class com.mcpintelligence.fr3k.VisibleToolsDeviceTest#termuxHealthMatchesCurrentPermissionAndRealProbe com.mcpintelligence.fr3k.hud.test/androidx.test.runner.AndroidJUnitRunner > "$OUT/$1.txt"
  rg -q 'OK \(1 test\)' "$OUT/$1.txt"
}
run_connected termux-connected
"${ADB[@]}" shell pm revoke com.mcpintelligence.fr3k.hud com.termux.permission.RUN_COMMAND
"${ADB[@]}" shell 'am instrument -w -r -e termuxExpected "PERMISSION REQUIRED" -e class com.mcpintelligence.fr3k.VisibleToolsDeviceTest#termuxHealthMatchesCurrentPermissionAndRealProbe com.mcpintelligence.fr3k.hud.test/androidx.test.runner.AndroidJUnitRunner' > "$OUT/termux-permission-denied.txt"
rg -q 'OK \(1 test\)' "$OUT/termux-permission-denied.txt"
"${ADB[@]}" shell pm grant com.mcpintelligence.fr3k.hud com.termux.permission.RUN_COMMAND
run_connected termux-permission-restored
"${ADB[@]}" shell am force-stop com.mcpintelligence.fr3k.hud
run_connected termux-hud-cold-recreated
printf 'PASS: connected, permission denied, permission restored, HUD process recreated.\n'
