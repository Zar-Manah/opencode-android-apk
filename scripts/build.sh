#!/usr/bin/env bash
# Build standalone release APK for OpenCode Android (arm64-v8a).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [ -z "${JAVA_HOME:-}" ] && [ -d "/opt/homebrew/opt/openjdk@17" ]; then
  export JAVA_HOME="/opt/homebrew/opt/openjdk@17"
fi
if [ -z "${ANDROID_HOME:-}" ] && [ -d "$HOME/Library/Android/sdk" ]; then
  export ANDROID_HOME="$HOME/Library/Android/sdk"
fi
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}$PATH"
cd "$ROOT"
./gradlew :app:assembleRelease
APK=$(ls app/build/outputs/apk/release/*-release-arm64-v8a.apk | head -1)
ls -lh "$APK"
