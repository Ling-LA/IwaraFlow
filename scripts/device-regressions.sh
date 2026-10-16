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


# Prove persistence across real process death, not only Activity recreation.
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
mkdir -p app/build/reports/ui-checks
for phase in prepare restore; do
  adb shell am force-stop com.ling.iwaraflow
  log="app/build/reports/ui-checks/warm-$phase.txt"
  adb shell am instrument -w -r \
    -e class 'com.ling.iwaraflow.WarmRecommendationDeviceTest#coldStartUsesEncryptedQueueAndPlaysCachedVideoWithExpiredUrl' \
    -e phase "$phase" com.ling.iwaraflow.test/androidx.test.runner.AndroidJUnitRunner > "$log"
  cat "$log"
  grep -F 'OK (1 test)' "$log"
done
