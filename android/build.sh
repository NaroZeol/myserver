#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
export MYSERVER_KEY_ALIAS="${MYSERVER_KEY_ALIAS:-myserver}"
: "${ANDROID_JAR:?Set ANDROID_JAR to Android platform 35 android.jar}"
: "${ANDROID_BUILD_TOOLS:?Set ANDROID_BUILD_TOOLS to build-tools 35.0.0}"
: "${JAVA_HOME:?Set JAVA_HOME to JDK 17 or later}"
: "${MYSERVER_KEYSTORE:?Set MYSERVER_KEYSTORE to a private signing keystore}"
: "${MYSERVER_KEYSTORE_PASSWORD_FILE:?Set MYSERVER_KEYSTORE_PASSWORD_FILE to its private password file}"
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
if os.environ.get('MYSERVER_PREVIEW') == '1':
    manifest = manifest.replace('package="app.thoughts.mobile"', 'package="app.thoughts.mobile.preview"').replace('android:label="myserver"', 'android:label="myserver·预览"')
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
"$ANDROID_BUILD_TOOLS/apksigner" sign --ks "$MYSERVER_KEYSTORE" --ks-key-alias "$MYSERVER_KEY_ALIAS" --ks-pass "file:$MYSERVER_KEYSTORE_PASSWORD_FILE" --out build/myserver.apk build/aligned.apk
"$ANDROID_BUILD_TOOLS/apksigner" verify --verbose build/myserver.apk
ls -lh build/myserver.apk
