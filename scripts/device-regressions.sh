#!/usr/bin/env bash
set -euo pipefail

# Preserve screenshots even if an assertion fails; the app can be uninstalled by Gradle.
collect_screenshots() {
  status=$?
  trap - EXIT
  mkdir -p app/build/reports/ui-checks
  if ! adb pull /sdcard/Download/IwaraFlow-ui-checks/. app/build/reports/ui-checks/; then
    if [ "$status" -eq 0 ]; then status=1; fi
  fi
  exit "$status"
}
trap collect_screenshots EXIT
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb shell settings put system screen_off_timeout 1800000
gradle :app:connectedDebugAndroidTest --no-daemon
gradle :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.backMode=key --no-daemon
