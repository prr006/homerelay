#!/usr/bin/env bash
# Verify the debug APK: package metadata, permissions, signature, checksum.
# Prints everything to stdout and into logs/apk-verify.txt, then (best effort)
# posts the report as a comment on issue #1 via ci-issue-comment.py.
set -euo pipefail
BT="$(ls -d "${ANDROID_HOME}"/build-tools/* 2>/dev/null | sort -V | tail -n1)"
if [[ -z "$BT" ]]; then
    echo "::error::no build-tools under ${ANDROID_HOME}"
    exit 1
fi
echo "build-tools: $BT"
APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"
if [[ ! -f "$APK" ]]; then
    echo "::error::missing $APK"
    exit 1
fi
mkdir -p logs
{
    echo "== file =="
    ls -la "$APK"
    echo
    echo "== sha256 =="
    sha256sum "$APK"
    echo
    echo "== aapt2 dump badging =="
    "$BT/aapt2" dump badging "$APK" | head -n 40
    echo
    echo "== aapt2 dump permissions =="
    "$BT/aapt2" dump permissions "$APK" || true
    echo
    echo "== apksigner verify =="
    "$BT/apksigner" verify --print-certs "$APK"
} | tee logs/apk-verify.txt
python3 tools/ci-issue-comment.py apk-verify.log verify-apk logs/apk-verify.txt || true
