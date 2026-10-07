#!/usr/bin/env bash
set -euo pipefail
# Run after the same 60-second local fixture playback on each device/build/network profile.
# No logcat, URLs, account data or media files are collected.
OUT="${1:-performance-report}"
mkdir -p "$OUT"
adb shell dumpsys gfxinfo com.ling.iwaraflow > "$OUT/frames.txt"
adb shell dumpsys meminfo com.ling.iwaraflow > "$OUT/memory.txt"
adb shell dumpsys batterystats --charged com.ling.iwaraflow > "$OUT/battery.txt"
adb shell getprop ro.build.version.sdk > "$OUT/android-api.txt"
printf '%s\n' 'Also export the in-app local diagnostics for first-frame percentiles, rebuffer duration and media bytes.'
