#!/usr/bin/env bash
# HomeRelay framework-free unit + integration tests.
# Runs on any JDK 11+ (javac) or with the Eclipse batch compiler (ECJ).
#
#   HOMERELAY_TOOLCHAIN=/path/to/toolchain ./tools/run-tests.sh
#
# The toolchain directory may contain:
#   android-31-api.jar   Android 12 platform jar with java.* removed (needed for ECJ only)
#   android-31.jar       raw Android 12 platform jar (javac only, fallback)
#
# If HOMERELAY_TOOLCHAIN does not provide a jar but $ANDROID_HOME/platforms/*/android.jar
# exists, the newest one is used with javac. In CI (javac present) no toolchain dir is needed.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TC="${HOMERELAY_TOOLCHAIN:-$HOME/toolchain}"
OUT="$ROOT/dist/test-classes"
rm -rf "$OUT"; mkdir -p "$OUT"

MAIN_SOURCES=$(find "$ROOT/app/src/main/java" -name '*.java' | sort)
TEST_SOURCES=$(find "$ROOT/tests" -name '*.java' | sort)

JAVAC="$(command -v javac || true)"
JAVA_BIN="${JAVA_BIN:-$(command -v java || true)}"

if [[ -z "$JAVA_BIN" ]]; then
    echo "error: java not found (set JAVA_BIN)" >&2
    exit 2
fi

ANDROID_JAR=""
if [[ -f "$TC/android-31-api.jar" ]]; then
    ANDROID_JAR="$TC/android-31-api.jar"          # filtered jar: safe for both javac and ECJ
elif [[ -f "$TC/android-31.jar" ]]; then
    ANDROID_JAR="$TC/android-31.jar"              # raw jar: javac only
elif [[ -n "${ANDROID_HOME:-}" ]] && ls "$ANDROID_HOME"/platforms/*/android.jar >/dev/null 2>&1; then
    ANDROID_JAR="$(ls "$ANDROID_HOME"/platforms/*/android.jar | sort -V | tail -n 1)"
    echo ">> using Android platform jar from SDK: $ANDROID_JAR"
else
    echo "error: no Android platform jar found (set HOMERELAY_TOOLCHAIN or ANDROID_HOME)" >&2
    exit 2
fi

if [[ -n "$JAVAC" ]]; then
    echo ">> compiling with javac ($JAVAC)"
    "$JAVAC" -nowarn -encoding UTF-8 -source 11 -target 11 -cp "$ANDROID_JAR" -d "$OUT" $MAIN_SOURCES $TEST_SOURCES
else
    ECJ="$TC/ecj.jar"
    if [[ ! -f "$ECJ" ]]; then
        echo "error: neither javac nor $TC/ecj.jar available" >&2
        exit 2
    fi
    if [[ "$ANDROID_JAR" != "$TC/android-31-api.jar" ]]; then
        echo "error: ECJ needs the filtered android-31-api.jar (raw android.jar confuses ECJ)" >&2
        exit 2
    fi
    echo ">> compiling with ECJ"
    "$JAVA_BIN" -cp "$ECJ" org.eclipse.jdt.internal.compiler.batch.Main \
        -source 11 -target 11 -nowarn -encoding UTF-8 -classpath "$ANDROID_JAR" \
        -d "$OUT" $MAIN_SOURCES $TEST_SOURCES
fi

TEST_CLASSES=$(cd "$ROOT/tests" && find . -name '*Test.java' | sed 's#^\./##; s#/#.#g; s#\.java$##' | sort | tr '\n' ' ')
echo ">> running tests: $TEST_CLASSES"
"$JAVA_BIN" -cp "$OUT" com.homerelay.app.TestRunner $TEST_CLASSES
