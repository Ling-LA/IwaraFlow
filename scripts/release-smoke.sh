#!/usr/bin/env bash
set -euo pipefail
package=com.ling.iwaraflow
mkdir -p out/release-smoke
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb logcat -c
# Exercise manifest-created classes/resources in the actual minified, privately signed release.
for page in MainActivityV3 SettingsActivity SearchActivity SavedVideosActivity InterestActivity DataManagementActivity MyActivity UploadActivity; do
  adb shell am start -W -n "$package/.$page" > "out/release-smoke/$page-start.txt"
  grep -Eq 'Status: ok|Warning: Activity not started' "out/release-smoke/$page-start.txt"
  sleep 2
  test -n "$(adb shell pidof "$package" | tr -d '\r')"
  adb shell dumpsys gfxinfo "$package" > "out/release-smoke/$page-gfx.txt"
  adb shell dumpsys meminfo "$package" > "out/release-smoke/$page-memory.txt"
  adb exec-out screencap -p > "out/release-smoke/$page.png"
  adb shell input keyevent KEYCODE_BACK
done
adb logcat -d -s AndroidRuntime:E > out/release-smoke/crashes.txt
if grep -q "Process: $package" out/release-smoke/crashes.txt; then
  cat out/release-smoke/crashes.txt
  exit 1
fi
echo 'PASS: optimized release starts all primary pages without a Java crash.'
