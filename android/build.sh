#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
# Compatibility with existing local/CI signing configurations.
export SERVER_KIT_KEYSTORE="${SERVER_KIT_KEYSTORE:-${THOUGHTS_KEYSTORE:-}}"
export SERVER_KIT_KEYSTORE_PASSWORD_FILE="${SERVER_KIT_KEYSTORE_PASSWORD_FILE:-${THOUGHTS_KEYSTORE_PASSWORD_FILE:-}}"
export SERVER_KIT_KEY_ALIAS="${SERVER_KIT_KEY_ALIAS:-thoughts}"
export SERVER_KIT_PREVIEW="${SERVER_KIT_PREVIEW:-${THOUGHTS_PREVIEW:-}}"
export SERVER_KIT_COMPILE_TEST_ONLY="${SERVER_KIT_COMPILE_TEST_ONLY:-${THOUGHTS_COMPILE_TEST_ONLY:-}}"
export SERVER_KIT_SSH_TEST="${SERVER_KIT_SSH_TEST:-${THOUGHTS_SSH_TEST:-}}"
: "${ANDROID_JAR:?Set ANDROID_JAR to Android platform 35 android.jar}"
: "${ANDROID_BUILD_TOOLS:?Set ANDROID_BUILD_TOOLS to build-tools 35.0.0}"
: "${JAVA_HOME:?Set JAVA_HOME to JDK 17 or later}"
: "${SERVER_KIT_KEYSTORE:?Set SERVER_KIT_KEYSTORE to a private signing keystore}"
: "${SERVER_KIT_KEYSTORE_PASSWORD_FILE:?Set SERVER_KIT_KEYSTORE_PASSWORD_FILE to its private password file}"
export PATH="$JAVA_HOME/bin:$PATH"
rm -rf build/generated build/classes build/dex
mkdir -p build/generated build/classes build/dex
bash dependencies.sh
python3 - <<'PY'
import os, shutil, hashlib, json
from pathlib import Path
terminal = Path('assets/terminal')
for name, digest in json.loads((terminal / 'vendor.json').read_text())['sha256'].items():
    assert hashlib.sha256((terminal / name).read_bytes()).hexdigest() == digest, 'Terminal asset checksum mismatch: ' + name
# Drop stale resources when switching deployment configurations.
shutil.rmtree('build/res', ignore_errors=True)
shutil.copytree('res', 'build/res')
manifest = Path('AndroidManifest.xml').read_text()
if os.environ.get('SERVER_KIT_PREVIEW') == '1':
    manifest = manifest.replace('package="app.thoughts.mobile"', 'package="app.thoughts.mobile.preview"').replace('android:label="想法"', 'android:label="想法·预览"')
    p = Path('build/res/xml/shortcuts.xml')
    p.write_text(p.read_text().replace('android:targetPackage="app.thoughts.mobile"', 'android:targetPackage="app.thoughts.mobile.preview"'))
Path('build/AndroidManifest.xml').write_text(manifest)
PY
"$ANDROID_BUILD_TOOLS/aapt2" compile --dir build/res -o build/resources.zip
"$ANDROID_BUILD_TOOLS/aapt2" link -o build/unsigned.apk -I "$ANDROID_JAR" --manifest build/AndroidManifest.xml -A assets --java build/generated build/resources.zip
find src build/generated -name '*.java' -print > build/sources.txt
javac -classpath build/deps/jsch-android.jar -encoding UTF-8 -source 8 -target 8 -bootclasspath "$ANDROID_JAR:$ANDROID_BUILD_TOOLS/core-lambda-stubs.jar" -d build/classes @build/sources.txt
jar cf build/classes.jar -C build/classes .
"$ANDROID_BUILD_TOOLS/d8" --release --min-api 26 --lib "$ANDROID_JAR" --output build/dex build/classes.jar build/deps/jsch-android.jar
python3 - <<'PY'
from zipfile import ZipFile, ZIP_DEFLATED
from pathlib import Path
with ZipFile('build/unsigned.apk', 'a', ZIP_DEFLATED) as apk:
    for dex in Path('build/dex').glob('*.dex'):
        apk.write(dex, dex.name)
PY
"$ANDROID_BUILD_TOOLS/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk
"$ANDROID_BUILD_TOOLS/apksigner" sign --ks "$SERVER_KIT_KEYSTORE" --ks-key-alias "$SERVER_KIT_KEY_ALIAS" --ks-pass "file:$SERVER_KIT_KEYSTORE_PASSWORD_FILE" --out build/server-kit.apk build/aligned.apk
"$ANDROID_BUILD_TOOLS/apksigner" verify --verbose build/server-kit.apk
ls -lh build/server-kit.apk
