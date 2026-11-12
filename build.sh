#!/usr/bin/env bash
# Builds the server without any third-party dependencies (JDK 21 only).
#   ./build.sh          compile and package build/app.jar
#   ./build.sh test     compile, package and run the test suite
set -euo pipefail
cd "$(dirname "$0")"
rm -rf build/classes build/test-classes
mkdir -p build/classes build/test-classes
find src/main/java -name '*.java' > build/sources.txt
javac --release 21 -encoding UTF-8 -Xlint:all,-serial,-rawtypes -d build/classes @build/sources.txt
jar --create --file build/app.jar --main-class ua.kpi.pocw.Main -C build/classes .
echo "built build/app.jar"
if [[ "${1:-}" == "test" ]]; then
  find src/test/java -name '*.java' > build/test-sources.txt
  javac --release 21 -encoding UTF-8 -cp build/classes -d build/test-classes @build/test-sources.txt
  java -cp build/classes:build/test-classes ua.kpi.pocw.testing.TestRunner
fi
