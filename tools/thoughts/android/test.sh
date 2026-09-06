#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
export PATH="$JAVA_HOME/bin:$PATH"
rm -rf build/test/classes build/test/dex
mkdir -p build/test/classes build/test/dex
python3 - <<'PY'
from pathlib import Path
import os
s=Path('test/AndroidManifest.xml').read_text()
if os.environ.get('THOUGHTS_PREVIEW')=='1':
    s=s.replace('android:targetPackage="app.thoughts.mobile"','android:targetPackage="app.thoughts.mobile.preview"')
Path('build/test/AndroidManifest.xml').write_text(s)
PY
"$ANDROID_BUILD_TOOLS/aapt2" link -o build/test/unsigned.apk -I "$ANDROID_JAR" --manifest build/test/AndroidManifest.xml
javac -encoding UTF-8 -source 8 -target 8 -bootclasspath "$ANDROID_JAR:$ANDROID_BUILD_TOOLS/core-lambda-stubs.jar" -classpath build/classes:build/deps/jsch-android.jar -d build/test/classes test/SmokeTest.java
jar cf build/test/classes.jar -C build/test/classes .
"$ANDROID_BUILD_TOOLS/d8" --release --min-api 26 --lib "$ANDROID_JAR" --classpath build/classes.jar --output build/test/dex build/test/classes.jar
python3 - <<'PY'
from zipfile import ZipFile, ZIP_DEFLATED
with ZipFile('build/test/unsigned.apk','a',ZIP_DEFLATED) as f:f.write('build/test/dex/classes.dex','classes.dex')
PY
"$ANDROID_BUILD_TOOLS/zipalign" -f -p 4 build/test/unsigned.apk build/test/aligned.apk
"$ANDROID_BUILD_TOOLS/apksigner" sign --ks "$THOUGHTS_KEYSTORE" --ks-key-alias thoughts --ks-pass "file:$THOUGHTS_KEYSTORE_PASSWORD_FILE" --out build/test/tests.apk build/test/aligned.apk
if [[ "${THOUGHTS_COMPILE_TEST_ONLY:-0}" != 1 ]]; then
  adb install -r build/thoughts.apk
  adb install -r build/test/tests.apk
  ssh_args=()
  if [[ "${THOUGHTS_SSH_TEST:-0}" == 1 ]]; then
    ssh_args=(-e ssh_host_key "$(cut -d' ' -f2 build/ssh-fixture/host.pub)" -e ssh_wrong_host_key "$(cut -d' ' -f2 build/ssh-fixture/wrong-host.pub)" -e ssh_user "$(id -un)" -e ssh_password "$(cat build/ssh-fixture/password)")
  fi
  adb shell am instrument -w "${ssh_args[@]}" app.thoughts.mobile.test/app.thoughts.mobile.SmokeTest | tee build/test/result.txt
  apk_package=app.thoughts.mobile
  [[ "${THOUGHTS_PREVIEW:-0}" == 1 ]] && apk_package=app.thoughts.mobile.preview
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
