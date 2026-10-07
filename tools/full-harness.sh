#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 -m unittest discover -s server/remote-vault -v
./gradlew testDebugUnitTest assembleDebugAndroidTest
./gradlew connectedDebugAndroidTest
./gradlew assembleDebug -Pforgegen.publish
