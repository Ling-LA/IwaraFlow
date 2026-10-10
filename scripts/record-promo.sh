#!/usr/bin/env bash
set -euo pipefail
mkdir -p promo-recordings
collect() {
  status=$?
  trap - EXIT
  adb pull /sdcard/Download/IwaraFlow-Android16-promo/. promo-recordings/ || true
  adb logcat -d -t 300 > promo-recordings/logcat.txt || true
  exit "$status"
}
trap collect EXIT
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
adb shell wm size 1080x1920
adb shell wm density 420
adb shell cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.gestural
adb shell settings put system screen_off_timeout 1800000
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 0
adb shell settings put system show_touches 1
adb shell settings put global window_animation_scale 0.5
adb shell settings put global transition_animation_scale 0.5
adb shell settings put global animator_duration_scale 1
{
  echo 'Android 16 native screen recording; IwaraFlow source version 0.16.10'
  adb shell getprop ro.build.version.release
  adb shell getprop ro.build.version.sdk
  adb shell getprop ro.build.fingerprint
  adb shell wm size
  adb shell wm density
  git rev-parse HEAD
} > promo-recordings/device-proof.txt
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e class com.ling.iwaraflow.PromoRecordingTest com.ling.iwaraflow.test/androidx.test.runner.AndroidJUnitRunner | tee promo-recordings/instrumentation.txt
grep -F 'OK (1 test)' promo-recordings/instrumentation.txt
