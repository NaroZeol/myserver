#!/usr/bin/env bash
# Compile the generic platform and terminal without any thoughts implementation on the classpath.
set -euo pipefail
cd "$(dirname "$0")"
: "${JAVA_HOME:?Set JAVA_HOME to JDK 17 or later}"
: "${ANDROID_JAR:?Set ANDROID_JAR}"
: "${ANDROID_BUILD_TOOLS:?Set ANDROID_BUILD_TOOLS}"
rm -rf build/boundaries
mkdir -p build/boundaries
find src/app/thoughts/mobile/core src/app/thoughts/mobile/modules/terminal -name '*.java' -print > build/boundaries/sources.txt
"$JAVA_HOME/bin/javac" -encoding UTF-8 -source 8 -target 8 -sourcepath '' -bootclasspath "$ANDROID_JAR:$ANDROID_BUILD_TOOLS/core-lambda-stubs.jar" -classpath build/deps/jsch-android.jar -d build/boundaries/classes @build/boundaries/sources.txt
echo 'PASS: core and terminal compile independently of thoughts and the application shell'
