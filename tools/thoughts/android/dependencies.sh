#!/usr/bin/env bash
set -euo pipefail
mkdir -p build/deps
jsch_jar=build/deps/jsch-2.28.7.jar
jsch_sha=4819f453e5d3dc277be2b91d49a0f87b7c4db36dc35c72c0d4bfeba56d2f94bd
if [[ ! -f "$jsch_jar" ]]; then
  curl --fail --silent --show-error --location --retry 3 --max-time 120 \
    https://repo.maven.apache.org/maven2/com/github/mwiede/jsch/2.28.7/jsch-2.28.7.jar -o "$jsch_jar.tmp"
  mv "$jsch_jar.tmp" "$jsch_jar"
fi
printf '%s  %s\n' "$jsch_sha" "$jsch_jar" | sha256sum --check --status
python3 - <<'PY'
from zipfile import ZipFile,ZIP_DEFLATED
# Android uses the Java 8 implementation, not multi-release Java 17+ classes.
with ZipFile('build/deps/jsch-2.28.7.jar') as source, ZipFile('build/deps/jsch-android.jar','w',ZIP_DEFLATED) as target:
    for entry in source.infolist():
        if entry.filename.endswith('.class') and not entry.filename.startswith('META-INF/'):
            target.writestr(entry.filename, source.read(entry))
PY
