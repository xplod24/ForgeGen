#!/usr/bin/env bash
# The owner's complete pre-vault harness, pinned to the supplied context commit.
set -euo pipefail
cd "$(dirname "$0")/.."
CONTEXT=97a980d3c97d4f359506d01fd5f045919c65a537
if ! git cat-file -e "$CONTEXT^{commit}" 2>/dev/null; then
    git fetch origin "$CONTEXT"
fi
git archive "$CONTEXT" agent-context/harness | tar -x
# The legacy JVM has no Android Keystore or WorkManager. Vault-off generation
# remains covered here; real encryption, transfers and recovery have app/server tests.
cat > agent-context/harness/src/main/kotlin/stubs/RemoteVault.kt <<'KOTLIN'
package com.example.forgegen
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
object RemoteVault {
    val enabled = MutableStateFlow(false)
    val automatic = MutableStateFlow(false)
    fun generated(file: File, metadata: String) { }
}
KOTLIN
./gradlew -p agent-context/harness test "$@"
