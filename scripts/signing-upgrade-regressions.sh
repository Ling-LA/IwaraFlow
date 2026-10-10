#!/usr/bin/env bash
set -euo pipefail
package=com.ling.iwaraflow
adb root
adb wait-for-device
adb install previous/IwaraFlow-signed.apk
# A marker owned by the old install must survive without uninstalling or clearing data.
adb shell mkdir -p /data/user/0/$package/files
uid=$(adb shell stat -c %u /data/user/0/$package | tr -d '\r')
adb shell "echo preserve-user-data > /data/user/0/$package/files/signing-upgrade-marker"
adb shell chown "$uid:$uid" /data/user/0/$package/files /data/user/0/$package/files/signing-upgrade-marker
adb install -r out/IwaraFlow-signed.apk
actual=$(adb shell cat /data/user/0/$package/files/signing-upgrade-marker | tr -d '\r')
test "$actual" = preserve-user-data
expected=$(grep -oP 'versionName\s*=\s*"\K[^"]+' app/build.gradle.kts | head -n 1)
adb shell dumpsys package "$package" | grep -F "versionName=$expected"
# Same application/version, old public key: rejection must be signature-based, not a downgrade.
if adb install -r "$RUNNER_TEMP/legacy-test.apk" > "$RUNNER_TEMP/legacy-rejection.txt" 2>&1; then
  echo 'ERROR: old signing key was allowed to replace the private-key release'
  exit 1
fi
cat "$RUNNER_TEMP/legacy-rejection.txt"
grep -F 'INSTALL_FAILED_UPDATE_INCOMPATIBLE' "$RUNNER_TEMP/legacy-rejection.txt"
# Reinstalling the current private-key APK remains valid.
adb install -r out/IwaraFlow-signed.apk
test "$(adb shell cat /data/user/0/$package/files/signing-upgrade-marker | tr -d '\r')" = preserve-user-data
echo 'PASS: old-to-new upgrade preserves data, private-key reinstall succeeds, old-key replacement is rejected.'
