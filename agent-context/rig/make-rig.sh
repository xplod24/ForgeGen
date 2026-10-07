#!/usr/bin/env bash
# Builds the Robolectric screenshot rig: a copy of this repository whose unit tests are replaced by ShotTest (Compose
# screens saved as PNG) and MigrationTest (Room migrations on a real SQLite), with the additions of rig.gradle.kts.txt.
# The repository itself is never changed. Usage: agent-context/rig/make-rig.sh [rig dir] [screenshot dir]
set -euo pipefail
REPO="$(cd "$(dirname "$0")/../.." && pwd)"
RIG="${1:-${TMPDIR:-/tmp}/forgegen-rig}"
SHOTS="${2:-$RIG/shots}"
mkdir -p "$RIG" "$SHOTS"
# A fresh copy of the code (main sources change with every task): everything but git data, builds and big files.
rm -rf "$RIG/app/src"
tar -C "$REPO" --exclude=./.git --exclude=./build --exclude=./app/build --exclude=./.gradle --exclude=./.kotlin \
    --exclude=./ktlint.jar --exclude=./app/release --exclude=./agent-context -cf - . | tar -C "$RIG" -xf -
rm -rf "$RIG/app/src/test/java"
mkdir -p "$RIG/app/src/test/java/com/example/forgegen"
cp "$REPO/agent-context/rig/ShotTest.kt" "$REPO/agent-context/rig/MigrationTest.kt" "$RIG/app/src/test/java/com/example/forgegen/"
sed "s|@SHOT_DIR@|$SHOTS|" "$REPO/agent-context/rig/rig.gradle.kts.txt" >> "$RIG/app/build.gradle.kts"
echo "Rig ready in $RIG (screenshots: $SHOTS). Run, for example:"
echo "  cd $RIG && ./gradlew :app:testDebugUnitTest --tests 'com.example.forgegen.ShotTest.z*' --tests 'com.example.forgegen.MigrationTest'"
