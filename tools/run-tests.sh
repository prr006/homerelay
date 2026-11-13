#!/usr/bin/env bash
# HomeRelay framework-free unit + integration tests.
# Runs on any machine with a JDK 11+ (javac) or with the ECJ batch compiler jar.
#
#   HOMERELAY_TOOLCHAIN=/path/to/toolchain ./tools/run-tests.sh
#
# Toolchain directory may contain:
#   android-31-api.jar (Android 12 platform jar, java.*-filtered, compile-time only)
#   ecj.jar          (Eclipse JDT batch compiler, used if javac is absent)
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TC="${HOMERELAY_TOOLCHAIN:-$HOME/toolchain}"
OUT="$ROOT/dist/test-classes"
rm -rf "$OUT"; mkdir -p "$OUT"

# Collect sources
MAIN_SOURCES=$(find "$ROOT/app/src/main/java" -name '*.java' | sort)
TEST_SOURCES=$(find "$ROOT/tests" -name '*.java' | sort)

ANDROID_JAR="$TC/android-31-api.jar"
if [[ ! -f "$ANDROID_JAR" ]]; then
    echo "error: android-31.jar not found in $TC (set HOMERELAY_TOOLCHAIN)" >&2
    exit 2
fi

if command -v javac >/dev/null 2>&1; then
    echo ">> compiling with javac"
    javac -nowarn -encoding UTF-8 -source 11 -target 11 -cp "$ANDROID_JAR" -d "$OUT" $MAIN_SOURCES $TEST_SOURCES
else
    ECJ="$TC/ecj.jar"
    if [[ ! -f "$ECJ" ]]; then
        echo "error: neither javac nor $TC/ecj.jar available" >&2
        exit 2
    fi
    JAVA_BIN="${JAVA_BIN:-$(command -v java || true)}"
    if [[ -z "$JAVA_BIN" ]]; then echo "error: java not found (set JAVA_BIN)" >&2; exit 2; fi
    echo ">> compiling with ECJ"
    "$JAVA_BIN" -cp "$ECJ" org.eclipse.jdt.internal.compiler.batch.Main \
        -source 11 -target 11 -nowarn -encoding UTF-8 -classpath "$ANDROID_JAR" \
        -d "$OUT" $MAIN_SOURCES $TEST_SOURCES
fi

# Determine JVM
JAVA_BIN="${JAVA_BIN:-$(command -v java || true)}"
TEST_CLASSES=$(cd "$ROOT/tests" && find . -name '*Test.java' | sed 's#^\./##; s#/#.#g; s#\.java$##' | sort | tr '\n' ' ')
echo ">> running tests: $TEST_CLASSES"
"$JAVA_BIN" -cp "$OUT" com.homerelay.app.TestRunner $TEST_CLASSES
