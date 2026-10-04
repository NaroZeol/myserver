#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
export MYSERVER_KEY_ALIAS="${MYSERVER_KEY_ALIAS:-myserver}"
export PATH="$JAVA_HOME/bin:$PATH"
rm -rf build/test/classes build/test/dex
mkdir -p build/test/classes build/test/dex
cp test/AndroidManifest.xml build/test/AndroidManifest.xml
"$ANDROID_BUILD_TOOLS/aapt2" link -o build/test/unsigned.apk -I "$ANDROID_JAR" --manifest build/test/AndroidManifest.xml
find test/src -name '*.java' -print > build/test/sources.txt
javac -encoding UTF-8 -source 8 -target 8 -bootclasspath "$ANDROID_JAR:$ANDROID_BUILD_TOOLS/core-lambda-stubs.jar" -classpath build/classes:build/deps/jsch-android.jar -d build/test/classes @build/test/sources.txt
jar cf build/test/classes.jar -C build/test/classes .
"$ANDROID_BUILD_TOOLS/d8" --release --min-api 26 --lib "$ANDROID_JAR" --classpath build/classes.jar --output build/test/dex build/test/classes.jar
python3 - <<'PY'
from zipfile import ZipFile, ZIP_DEFLATED
with ZipFile('build/test/unsigned.apk','a',ZIP_DEFLATED) as f:f.write('build/test/dex/classes.dex','classes.dex')
PY
"$ANDROID_BUILD_TOOLS/zipalign" -f -p 4 build/test/unsigned.apk build/test/aligned.apk
"$ANDROID_BUILD_TOOLS/apksigner" sign --ks "$MYSERVER_KEYSTORE" --ks-key-alias "$MYSERVER_KEY_ALIAS" --ks-pass "file:$MYSERVER_KEYSTORE_PASSWORD_FILE" --out build/test/tests.apk build/test/aligned.apk
if [[ "${MYSERVER_COMPILE_TEST_ONLY:-0}" != 1 ]]; then
  adb install -r build/myserver.apk
  adb install -r build/test/tests.apk
  apk_package=app.thoughts.mobile
  if (( $(adb shell getprop ro.build.version.sdk | tr -d '\r') >= 33 )); then
    adb shell pm grant "$apk_package" android.permission.POST_NOTIFICATIONS
  fi
  ssh_args=()
  if [[ "${MYSERVER_SSH_TEST:-0}" == 1 ]]; then
    ssh_args=(-e ssh_host_key "$(cut -d' ' -f2 build/ssh-fixture/host.pub)" -e ssh_wrong_host_key "$(cut -d' ' -f2 build/ssh-fixture/wrong-host.pub)" -e ssh_user "$(id -un)" -e ssh_password "$(cat build/ssh-fixture/password)")
  fi
  adb shell am instrument -w "${ssh_args[@]}" app.thoughts.mobile.test/app.thoughts.mobile.SmokeTest | tee build/test/result.txt
  if grep -q 'PASS: native launch' build/test/result.txt; then
    adb shell am instrument -w -e mode visual -e suffix -standard app.thoughts.mobile.test/app.thoughts.mobile.SmokeTest | tee build/test/visual.txt
    adb shell wm size 640x1280
    adb shell wm density 320
    adb shell settings put system font_scale 1.3
    adb shell am instrument -w -e mode visual -e suffix -compact app.thoughts.mobile.test/app.thoughts.mobile.SmokeTest | tee build/test/visual-compact.txt
    adb shell settings put system font_scale 1.0
    adb shell wm size reset
    adb shell wm density reset
  fi
  adb pull "/sdcard/Android/data/$apk_package/files/screenshots" build/test/ || true
  if ! grep -q 'PASS: native launch' build/test/result.txt ||
     ! grep -q 'PASS: visual review' build/test/visual.txt ||
     ! grep -q 'PASS: visual review' build/test/visual-compact.txt; then
    [[ -f build/ssh-fixture/sshd.log ]] && cat build/ssh-fixture/sshd.log
    adb logcat -d -b crash
    exit 1
  fi
fi
