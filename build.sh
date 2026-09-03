#!/usr/bin/env bash
# FR3K HUD build script — Java 17 + AGP 8.7.3 + Kotlin 2.0.21 + Compose
# Produces app/build/outputs/apk/debug/app-debug.apk
set -euo pipefail
cd "$(dirname "$0")"

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
export ANDROID_HOME="${ANDROID_HOME:-/usr/lib/android-sdk}"
export PATH="$JAVA_HOME/bin:$PATH"

echo "[build] JAVA_HOME=$JAVA_HOME"
echo "[build] ANDROID_HOME=$ANDROID_HOME"
echo "[build] gradle wrapper: $(./gradlew --version 2>/dev/null | grep -E '^Gradle ' || true)"

./gradlew --no-daemon -q "$@" :app:assembleDebug

APK="$(pwd)/app/build/outputs/apk/debug/app-debug.apk"
echo "[build] artifact: $APK"
ls -lh "$APK"
"$ANDROID_HOME/build-tools/35.0.1/apksigner" verify "$APK"
echo "[build] OK"