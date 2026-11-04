# Optional ForgeGen Bridge integration

ForgeGen 3.10.0 implements the supplied Bridge API 1.0 contract. The extension is optional and disabled by default
for each Forge server address. Generation, the phone's queue and the IIB gallery continue to use their existing
APIs. Enabling Bridge never replaces those clients or routes generation through the extension.

The supplied [integration guide](APP_INTEGRATION.md), [endpoint descriptions](ENDPOINTS.md),
[OpenAPI specification](openapi.json) and [full capability fixture](capabilities-full.json) are reference data.
The full fixture describes synthetic authorized behavior; the application always uses the connected server's
actual capabilities, scopes, launch flags and privacy policy.

## Pairing and access

1. Install/configure the Bridge extension on the Forge server and use its administrator panel to issue a
   short-lived invitation with the desired device scopes.
2. Select that Forge server profile in the app. Open Settings > Server > ForgeGen Bridge and enable it.
3. Scan the administrator's QR code, or paste its invitation JSON. Check the displayed address, server identity
   and expiry, choose a device name and confirm pairing. Camera access is requested only when opening the scanner;
   pasting JSON works without it. The scanner decodes locally.
4. The app checks public `/forgegen/v1/info` before sending the invitation code, verifies the returned device
   and reads capabilities. It never asks for extra scopes or creates an administrator invitation itself.

Bridge URLs preserve a Forge reverse-proxy path. Redirects are rejected so a device token cannot follow a redirect
to another origin. A changed server UUID requires explicit verification and re-pairing. Use your own trusted
HTTPS/VPN connection as appropriate for the server. A public HTML login page or an upstream 401 is displayed as
a Bridge access problem; it does not rewrite the Forge address or the gallery key.

The device token and unresolved operation intentions are encrypted with an Android Keystore AES-256-GCM key in
`noBackupFilesDir/forgegen-bridge`, separately for each canonical base URL. They are excluded from AppConfig,
settings exports, Android backup and saved UI state. Disabling Bridge keeps the pairing and any unresolved request;
Forget pairing removes this phone's local Bridge record. Revoking a device on the server removes its access.

## Tools and restrictions

- **Models:** registered roots and counts, catalog/details, scan, move and delete. The actual `count_only` policy
  exposes counts while withholding names and file actions. Counts cover top-level files as documented by the server.
- **Downloads:** direct Civitai source URLs or exact provider version/file selection, registered destinations,
  optional expected SHA-256, transfer history, cancellation and explicit retry. Transfers run on the server.
- **Civitai:** optional server-side search, model/license/version/file details. Configure the provider API key only
  in the Bridge server panel. The app neither collects nor stores that key. Search can remain available with
  counts-only local access, while downloading remains blocked by policy. No unsupported thumbnails, ratings or
  trigger-word fields are fabricated.
- **Extensions:** install/uninstall, next-start switches, bulk enable/disable/uninstall and disable-others.
  Mutations require `--enable-insecure-extension-access`, appropriate device scopes and the server's own restrictions.
  Bridge and built-in entries are protected. Current state, next-start state and restart requirement stay separate.
- **Server:** status/memory/errors, unload and restart with an explicit wait-for-idle choice. Completion of a restart
  request is an acknowledgment, not proof that the restarted process is ready.
- **Operations:** shared authorized server operations, phase/progress/bytes/result/error and cancellation when granted.
  Unknown totals remain indeterminate. Interrupted administrative operations require review rather than automatic retry.
- **Devices:** this phone's granted scopes, authorized device list and explicit revocation.

All mutations carry one UUID Idempotency-Key per user intention, including requests with an absent body. An uncertain
acknowledgment remains encrypted on disk; Retry same request reuses the exact method, body, query and key. A retry
rejection does not silently discard the original uncertainty. Review Operations before deliberately forgetting it.
No mutations are retried automatically. Read polling runs only while its view is in the foreground and stops on error.

## Synthetic verification

The owner performs live home-lab testing. Automated tests use fictional loopback servers and never contact the
owner's Forge installation or Civitai, install extensions, or download real models.

With JDK 21, Android SDK 37 and Python 3 available:

```sh
bash gradlew testDebugUnitTest assembleDebugAndroidTest
# Requires a connected Android emulator/device. Uses only local fictional fixtures.
bash gradlew connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
# Required publication variant; ordinary unit/emulator tests run unshrunk code.
bash gradlew assembleDebug -Pforgegen.publish
```

`BridgeClientContractTest` walks all 35 supplied OpenAPI operations and OPTIONS, validates request/response schemas,
decodes every generated DTO and checks authorization, empty DELETE/retry bodies and idempotency.
`BridgeControllerTest` exercises disabled isolation, pairing, identity changes, capabilities/privacy restrictions,
lost acknowledgments, controller recreation, revocation and profile switching. `BridgeProtocolTest` checks
invitation validation and a local QR encode/decode round trip.

Connected Compose tests exercise the real controller and production screens against a Java loopback fixture:
settings navigation, opt-in/pairing, model details, cancelled destructive confirmation, protected extensions,
next-start mutation, counts-only controls and exact Civitai file submission. Screenshots are written under
`files/ui-screenshots`. `BridgePrivateStoreTest` verifies the real Android Keystore store and profile isolation.
The existing complete legacy harness and vault checks remain required in CI and publication workflows.

The Python fixture can also be started manually for isolated development:

```sh
python3 server/bridge-harness/server.py --port 8765 --scenario count-only --credential-file /tmp/bridge-fixture.json
```

It binds to `127.0.0.1` only. Scenarios: `full`, `read-only`, `count-only`, `missing-flag`, `civitai-disabled`,
`revoked`, `absent`, `html`, `timeout`, `incompatible`, `server-changed`, `wrong-id`. The private temporary credential
file contains synthetic values. Do not commit it. Test-only `/__harness/*` controls are never part of production APIs.

## Contract maintenance

`python3 tools/bridge-contract.py` generates Android DTOs, compact validation schemas and the test route catalog from
the supplied OpenAPI. Format the generated Kotlin using the repository's ktlint before committing. DTOs stay in
`com.example.forgegen` so the existing R8/Gson field/constructor rules apply. Responses tolerate additive fields
while validating required shapes, supported protocol and identities. Client-known capabilities are an explicit set;
an unknown capability never unlocks a mutation.

The offline scanner uses [ZXing Android Embedded 4.3.0](https://github.com/journeyapps/zxing-android-embedded)
and ZXing core under Apache License 2.0; see [third-party notices](THIRD_PARTY_NOTICES.md).

## Bridge 1.2 additions

The pinned contract includes selected-file scans, pairing-only mode and owner-granted administrator access.
Administrator invitations are created in the server panel or native Windows launcher. Select Pair as administrator
on the phone; require_admin_access is a requirement, never a client-issued grant. Device.admin_access reports the
server decision. Existing paired devices are not promoted: revoke/forget and pair with a new owner-issued
administrator invitation when changing access. Owner privacy, launch flags and protected resources still apply.

Selected-file scans use POST /models/scan-selected and only the explicit root/path entered by the user. The
capability is independent of bulk model access. Optional lookup_civitai requires server configuration and scope.

For local Windows unit tests, set FORGEGEN_TEST_PYTHON and VAULT_PYTHON to an installed Python executable before
running gradlew.bat testDebugUnitTest. CI uses python3. All fixtures are loopback-only and contain fictional data.
