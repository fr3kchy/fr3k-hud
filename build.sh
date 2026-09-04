#!/usr/bin/env bash
# FR3K HUD build script — Java 17 + AGP 8.7.3 + Kotlin 2.0.21 + Compose
#
# Produces app/build/outputs/apk/debug/app-debug.apk and copies a
# version-derived artefact into artifacts/ for release verification.
#
# The artefact name is derived from app/build.gradle.kts so a version bump
# is a single edit; do not type a literal version string in this script.
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
if [[ ! -f "$APK" ]]; then
  echo "[build] FAILED: expected APK at $APK" >&2
  exit 1
fi

# Derive artefact metadata from app/build.gradle.kts — never type a literal
# version here. Both fields are required; fail loudly if either is missing
# so the contract test cannot pass with stale metadata.
VERSION_NAME="$(grep -E '^[[:space:]]+versionName' app/build.gradle.kts \
  | head -n1 | sed -E 's/.*"([^"]+)".*/\1/')"
VERSION_CODE="$(grep -E '^[[:space:]]+versionCode' app/build.gradle.kts \
  | head -n1 | sed -E 's/.*=[[:space:]]*([0-9]+).*/\1/')"
if [[ -z "$VERSION_NAME" || -z "$VERSION_CODE" ]]; then
  echo "[build] FAILED: could not derive versionName/versionCode from app/build.gradle.kts" >&2
  exit 1
fi

ARTIFACT_DIR="$(pwd)/artifacts"
mkdir -p "$ARTIFACT_DIR"
ARTIFACT="$ARTIFACT_DIR/FR3K-HUD-${VERSION_NAME}.apk"
cp -f "$APK" "$ARTIFACT"

echo "[build] artifact: $ARTIFACT"
ls -lh "$ARTIFACT"
"$ANDROID_HOME/build-tools/35.0.1/apksigner" verify --verbose "$ARTIFACT" || {
  echo "[build] apksigner verification FAILED" >&2
  exit 1
}

# SHA-256 for downstream release verification.
SHA="$(sha256sum "$ARTIFACT" | awk '{print $1}')"
echo "[build] sha256: $SHA"
echo "$SHA  $(basename "$ARTIFACT")" > "$ARTIFACT.sha256"

# Write a sidecar json so release tooling can read version + hash without
# re-parsing the gradle file.
cat > "$ARTIFACT.meta.json" <<EOF
{"appId":"com.mcpintelligence.fr3k.hud","versionName":"$VERSION_NAME","versionCode":$VERSION_CODE,"sha256":"$SHA","builtAt":"$(date -u +%Y-%m-%dT%H:%M:%SZ)"}
EOF

echo "[build] OK"