#!/usr/bin/env bash
# FR3K HUD test script — runs JVM unit tests on :core and :protocol
set -euo pipefail
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/java-17-openjdk-amd64}"
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew --no-daemon -q :core:test :protocol:test
echo "[test] OK"