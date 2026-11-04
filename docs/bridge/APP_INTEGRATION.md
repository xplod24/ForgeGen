# ForgeGen Android integration contract — API 1.0

The Android application is not changed by this server package. Implement its
bridge support as an optional capability provider alongside existing Forge and
Infinite Image Browsing clients. Never route basic generation through this API.

## Discovery and authentication boundaries

1. Keep the configured Forge base URL and existing Forge credentials.
2. Append `/forgegen/v1/info` to that base URL, preserving a configured subpath.
3. Require JSON, `bridge_id == "forgegen-bridge"`, a supported major API version,
   SemVer `bridge_version`, and a stable UUID `server_id`.
4. Retrieve `/forgegen/v1/openapi.json` if needed. It is generated from the actual
   bridge router; the checked-in document is tested for equality with it.
5. Without a paired token, retain only public discovery. Pairing authorizes this
   API, not Android permissions, standard Forge APIs, IIB, or an upstream proxy.

Bridge requests use `Authorization: Bearer` with a device token. There are no
token query parameters. Existing proxy/Forge authentication is not disabled or
replaced. A reverse proxy may challenge before a bridge route runs; treat that
as a separate server-authentication requirement. Store its configuration and the
bridge token separately. A bridge token does not authorize standard generation.

An optional `X-ForgeGen-API-Version: 1.0` header states the requested contract.
An unsupported value receives HTTP 426 `INCOMPATIBLE_PROTOCOL`. Initial discovery
can omit the header and inspect the returned version. Do not guess compatibility
from a successful TCP connection, HTML page, or a different extension's response.

Public responses contain no device list, logs, private state location or host
filesystem paths. All bridge responses use `Cache-Control: no-store` and carry
`X-Request-ID` and `X-ForgeGen-API-Version`. No global Forge middleware or exception
handler is replaced. Unknown paths under the bridge prefix have a local 404
envelope. `OPTIONS` returns allowed methods for known bridge paths without
relaxing the host's CORS policy.

## Pairing

An owner uses the password-protected bridge panel or the local OS-owner tool to
create an invitation. There is no anonymous invitation/admin-grant API.

The QR is JSON with these fields; no usable invitation is embedded in these docs:

| Field | Type | Meaning |
| --- | --- | --- |
| format | string | `forgegen-pairing` |
| format_version | integer | `1` |
| server_url | string | Owner-selected reachable base URL, including VPN/subpath |
| server_id | UUID string | Stable identity to confirm after connecting |
| invitation_id | UUID string | Identifies one invitation |
| code | secret string | Random single-use value; never log or export |
| expires_at | UTC timestamp | Normally 120 seconds after issue |

Send `POST /pairing/exchange` with `invitation_id`, `code`, `device_name`, and an
`Idempotency-Key`. The response has `device`, `token_type` and `access_token`.
The exact types are in OpenAPI; there is deliberately no token-bearing example.
The device object contains `id`, `name`, `scopes`, `created_at`, `revoked_at`.
Scopes come only from the saved invitation. Adding a `scopes` request field is
rejected, as are other unknown body fields.

Invitations expire and are consumed atomically. Identical exchange retries may
retrieve the original response only while the invitation is still valid and
the resulting device is not revoked. A different idempotency key cannot reuse
the code. Five invalid attempts lock that invitation; additional per-client and
global rate limits survive service recovery. Device tokens remain valid until
revoked. Persist them in Android's appropriate protected storage, not logs.

After pairing, verify `/devices/me`, then read `/capabilities`. Revocation gives
HTTP 401 `TOKEN_REVOKED`; unknown/missing credentials give `AUTH_REQUIRED`.
Do not automatically repeat pairing or widen scopes in response to HTTP 403.

## Capability semantics

Each entry has independent booleans:

- `supported`: implemented and backed by an available backend mechanism;
- `enabled`: allowed by owner/server configuration and required Forge flags;
- `authorized`: allowed by this device's saved scopes;
- `reason_code`: a stable reason for current unavailability, otherwise null.

For example, an extension mutation can be implemented and authorized but have
`enabled: false` and `FLAG_REQUIRED`. Read-only devices retain listing while
management has `authorized: false`. The local deployment reports
`privacy_policy: "count_only"`; catalog, scan, move, delete and downloads are
disabled with `PRIVACY_POLICY`. This is a real server-side denial, not a hidden
button. Do not present the full synthetic harness capabilities as live support.

Reason codes include `FLAG_REQUIRED`, `SCOPE_REQUIRED`, `NOT_CONFIGURED`,
`UNSUPPORTED_BACKEND`, `MODULE_DISABLED`, `BRIDGE_DISABLED`, `FORGE_RESTRICTED`,
and `PRIVACY_POLICY`. Preserve unknown future codes as an unavailable state.
Cleanup/readback functions such as revocation and operation inspection remain
available independently of starting new work. Cancellation remains possible
when the download module is switched off, subject to scope and owner policy.

## Endpoint map

All paths below are relative to `/forgegen/v1`. The OpenAPI document is
authoritative for fields, bounds, nullable values, response models and methods.

| Method | Path | Authorization / behavior |
| --- | --- | --- |
| GET | `/info` | Public bridge identity and API version |
| GET | `/openapi.json` | Public OpenAPI 3.1 document |
| POST | `/pairing/exchange` | One-time invitation; no device token yet |
| GET | `/devices/me` | Any valid device token |
| GET | `/capabilities` | Any valid device token |
| GET | `/devices` | `devices.manage` |
| DELETE | `/devices/{device_id}` | `devices.manage`; immediate revocation |
| GET | `/models/roots` | `models.read`; root IDs/types, no host paths |
| GET | `/models/counts` | `models.read`; direct counts only, no recursion |
| GET | `/models` | `models.read`; catalog, owner policy applies |
| GET | `/models/{model_id}` | `models.read`; registered model UUID |
| POST | `/models/scan` | `models.manage`; durable operation |
| POST | `/models/{model_id}/move` | `models.manage`; registered roots, safe name |
| DELETE | `/models/{model_id}` | `models.manage`; no arbitrary file path |
| GET | `/downloads` | `downloads.manage`; durable transfer operations |
| GET | `/downloads/{operation_id}` | `downloads.manage` |
| POST | `/downloads` | `downloads.manage`; configured source host/root |
| POST | `/downloads/{operation_id}/retry` | Retry a failed/interrupted/cancelled transfer |
| DELETE | `/downloads/{operation_id}` | Cancel; retain an inspectable operation |
| GET | `/civitai/models` | `civitai.search`; query/offset/limit |
| GET | `/civitai/models/{model_id}` | `civitai.search`; provider integer ID |
| GET | `/civitai/versions/{version_id}` | `civitai.search`; versions contain file projections |
| POST | `/civitai/downloads` | `downloads.manage`; exact version/file/root IDs |
| GET | `/extensions` | `extensions.read` |
| POST | `/extensions` | Install; flag + `extensions.manage` + Forge restrictions |
| PATCH | `/extensions/{extension_id}` | Change next-start enabled state |
| DELETE | `/extensions/{extension_id}` | Uninstall to quarantine; restart required |
| POST | `/extensions/bulk` | Enable/disable/uninstall or `disable_others` |
| GET | `/server/status` | `status.read`; no prompt/image data |
| POST | `/server/unload` | `server.manage`; supported idle hook |
| POST | `/server/restart` | `server.manage`; supported supervised restart hook |
| GET | `/operations` | Filtered by operation kind and corresponding manage scope |
| GET | `/operations/{operation_id}` | Corresponding model/download/extension/server scope |
| DELETE | `/operations/{operation_id}` | Request cancellation; no arbitrary command |

Every POST, PATCH and DELETE requires `Idempotency-Key`, including exchange and
revocation. Extension mutations require the Forge flag at admission **and again
at execution**. Scope and revocation are also checked again before queued work
runs. A phone disconnect is not a revocation and does not remove its queue.

Collections return `items`, `offset`, `limit`, `total`. Limits are 1–100 and
offsets are nonnegative. A provider may omit its total; then `total` is null.
Civitai offsets must align to the requested page size. UUID model IDs are local
resource identities; Civitai model/version/file IDs are provider integers.

## Idempotency and long operations

Generate a new random key for each user intention and keep it while retrying
that intention. The server fingerprints method, resource path and normalized
request content within the device identity. Reusing the key with different
content returns HTTP 409 `IDEMPOTENCY_CONFLICT`.

Long work returns HTTP 202 with `operation_id` and `status_url`. The queued record
and that acknowledgment are committed with SQLite FULL synchronization before
the acknowledgment is returned. If a phone loses the connection immediately,
retry with the **same** key to recover the same operation ID, then poll it.
Do not submit an identical operation with a new key just because polling timed out.

Operation fields are `id`, `kind`, `status`, `phase`, nullable `progress`, nullable
`bytes_done`/`bytes_total`, UTC `created_at`/`updated_at`, nullable `result`/`error`,
and `restart_required`. `progress` is null when unknown, not an invented percentage.
Phases such as waiting for idle and verification normally have null progress.

States: `queued`, `waiting_idle`, `running`, `completed`, `interrupted`, `failed`,
`cancelled`. Cancellation is cooperative; an already committed atomic action
can complete before a cancellation request takes effect. Cancellation does not
erase history. On restart, uncompleted active operations become `interrupted`.
Queued transfers survive recovery; interrupted transfers require explicit retry.
Administrative interruptions require review, not an automatic second deletion.

For transfers, a verified file is published without overwriting any destination,
then registered and refreshed. An existing destination produces `DESTINATION_EXISTS`.
No model-file operation is allowed while that resource may still be resident;
unload it first. Core generation activity also gates conflicting operations.
`wait_for_idle` allows an administrative operation to wait instead of immediate
`RESOURCE_BUSY`. Transfers always wait for idle before publication and refresh;
their stream worker never reserves the generation queue for the network transfer.
For a transfer, `wait_for_idle: true` additionally waits before contacting its
source; that initial wait does not reserve the generation queue for the stream.

Extension responses separate `current_enabled`, `next_enabled`, `installed`,
`restart_required`. Removing files or changing next-start configuration does not
unload existing Python imports. Bridge protection is based on canonical directory
identity, including aliases, not its display label. An invalid protected target
rejects a bulk request before any action. `disable_others` selects safe targets
itself and never sets a global disable-all option.

Restart operation completion records `restart_requested` and `requires_reconnect`
before calling Forge's supported hook. The process may disappear before its
HTTP response arrives. Reconnect to the same `server_id` and recover the operation
using its ID or the original idempotency key. A completed request is not proof
that a fresh server has finished starting.

## Errors and client fallback

The bridge envelope has `error.code`, English `error.message`,
`error.retryable`, and `request_id`. Messages contain no exception traceback,
request body, token, provider credential or filesystem path. Standard Forge
errors outside this prefix retain their original shape.

| HTTP | Common code | Client action |
| --- | --- | --- |
| 401 | `AUTH_REQUIRED`, `TOKEN_REVOKED`, `PAIRING_INVALID` | Pair or reauthenticate explicitly; retain base Forge access |
| 403 | `SCOPE_REQUIRED`, `FLAG_REQUIRED`, `PRIVACY_POLICY`, `MODULE_DISABLED` | Explain the exact disabled function; do not retry as admin |
| 404 | `MODEL_NOT_FOUND`, `OPERATION_NOT_FOUND`, `ENDPOINT_NOT_FOUND` | Refresh the relevant bridge view; a missing `/info` can mean no bridge |
| 409 | `IDEMPOTENCY_CONFLICT`, `RESOURCE_BUSY`, `RESOURCE_IN_USE`, `DESTINATION_EXISTS` | Resolve the specific conflict; waiting can be requested where supported |
| 413/422 | `REQUEST_TOO_LARGE`, `INVALID_REQUEST`, `HASH_MISMATCH` | Correct input or inspect transfer failure; never publish a bad hash |
| 426 | `INCOMPATIBLE_PROTOCOL` | Disable only bridge integration for this server |
| 429 | `RATE_LIMITED`, `QUEUE_FULL` | Back off; preserve idempotency keys |
| 501 | `UNSUPPORTED_BACKEND` | Mark only that capability unsupported |
| 502/503 | provider/network/configuration codes | Show bridge/provider unavailability, not a generation failure |
| 507 | `INSUFFICIENT_SPACE` | Free space before explicitly retrying |

- **No bridge / HTTP 404:** a host may return ordinary Forge JSON or HTML rather
  than this envelope. Missing bridge discovery never disables normal generation,
  existing downloads/IIB, or the user's configured server.
- **Timeout:** use a bounded discovery timeout. Keep the base Forge client usable.
  A timeout after a mutation can mean the operation was accepted; use the same key.
- **HTTP 401:** distinguish a known `TOKEN_REVOKED` body from unknown/missing auth,
  an expired pairing code, or an upstream proxy challenge. Clear/re-pair only the
  bridge credential when appropriate; do not erase standard Forge credentials.
- **Wrong major version:** do not attempt guessed endpoint shapes or admin calls.
- **Single disabled module:** keep all other authorized modules and base features.

## Fixtures and harness

`examples/manifest.json` maps 14 captured fictional responses to exact OpenAPI
operation/HTTP status schemas. It covers full and read-only capabilities, a
missing flag/scope, Civitai disabled, the real owner policy, queued/running/
completed/interrupted/failed work, required authentication and incompatible API.
No token, invitation code or API key appears in those examples.

`tests/harness_server.py` runs only on loopback, without Forge/GPU. Use scenarios
`full`, `read-only`, `missing-flag`, `civitai-disabled`, `count-only`, `revoked`,
`absent`, `timeout`, `incompatible`. `--token-file` optionally writes a temporary
local test credential rather than printing it. All model resources in this
harness are synthetic; its full policy must not be applied to this installation.

```powershell
venv\Scripts\python.exe extensions\forgegen-bridge\tests\harness_server.py --port 8765 --scenario count-only
venv\Scripts\python.exe extensions\forgegen-bridge\tools\smoke_test.py --server-url http://127.0.0.1:8765
```

The smoke tool reads only discovery, OpenAPI and (if explicitly supplied a token)
capabilities. It never reads prompts, models, images or runs administration.
