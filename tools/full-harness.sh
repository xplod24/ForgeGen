#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 -m unittest discover -s server/remote-vault -v
bash tools/legacy-harness.sh
./gradlew testDebugUnitTest assembleDebugAndroidTest
./gradlew connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
./gradlew assembleDebug -Pforgegen.publish
