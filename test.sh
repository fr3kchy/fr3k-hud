#!/usr/bin/env bash
# FR3K HUD test script — runs JVM unit tests in every Gradle module.
# No `-q`: a fail-closed CI must print which test failed, not just that the build
# failed. Exit status is Gradle's, so a failing test fails the script.
set -euo pipefail
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew --no-daemon test
echo "[test] OK"
