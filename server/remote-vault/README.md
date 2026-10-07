# Secure Remote Vault server

Optional single-owner opaque storage for ForgeGen. Forge WebUI Neo remains the generation server; this service only receives authenticated encrypted envelopes. Images, thumbnails, names, generation metadata and per-image keys are encrypted on the phone. The service sees ciphertext sizes, opaque identifiers and transfer/deletion times.

## Install on your own Linux host

Requires Python 3 and Docker Compose. It runs on ARM64 or x86_64; a Raspberry Pi is optional. Copy this directory to a private location owned by the deployment user (UID 1000 by default).

1. Copy `.env.example` to `.env`. Set `VAULT_BIND` to the host's VPN address and `VAULT_HOST` to the same IP or a DNS name. Keep the port private to your VPN; only the HTTPS service is published.
2. Run `python3 provision.py` and `mkdir -p tls caddy-config`. The provisioning tool creates a private bearer token and stores only its hash in the server configuration. Protect the directory with `chmod -R go-rwx .`.
3. Run `docker compose up -d --build`. Check `docker compose ps`.
4. Run `python3 provision.py https://YOUR_VPN_ADDRESS:8443` after HTTPS has started. Transfer the resulting **private** `connection.json` securely to the phone. It contains access credentials and the HTTPS trust certificate; it contains no image decryption key. Delete unnecessary exported copies afterwards.
5. In ForgeGen, open Settings > Backup & Data > Secure Remote Vault and import the connection file. Create a new vault or restore an existing one using its recovery code.

Caddy issues a private TLS certificate. The app trusts the imported CA only for this connection and still verifies the server hostname. No plaintext fallback or redirect is accepted. Default data quota: 100 GiB; maximum opaque object: 128 MiB. The app accepts source images up to 120 MiB, leaving room for the encrypted thumbnail and envelope. Change `VAULT_QUOTA` on the backend container if needed.

## Recovery and deletion

The phone generates an independent random recovery code once. Only a root-key envelope encrypted by that code reaches the server. The app never saves the code; its optional text export is explicit. Ordinary app settings exports and Android backups exclude vault keys and queues. A new phone requires a connection file **and** the recovery code. If both the phone key and the code are lost, recovery is impossible.

Deletion moves only vault objects to a 24-hour trash. Repeated deletion does not extend the deadline. Expired ciphertext, including its wrapped image key, is unlinked and removed from the database; expiry runs continuously and before access, including after restart. There is no guarantee of forensic erasure on microSD/SSD. Previously exported copies and snapshots are outside this deletion policy. Restoring an old backup can restore old objects; run the purge before serving it.

## Manual backup and restore

There is deliberately **no backup schedule**. Run `sh backup.sh` manually. It briefly stops the backend, purges expired objects and creates a snapshot with a SHA-256 checksum under `backups/`. This directory on the same microSD is not protection against loss of the card; copy it to an independent destination when available. Snapshots contain encrypted data and server credentials/certificates, but no phone decryption key.

To restore to an empty deployment directory, verify `sha256sum -c <archive>.sha256`, extract the trusted archive there, restore ownership to the deployment UID, run `python3 -c 'from server import Store; s=Store("data"); s.purge(); s.db.close()'`, then `docker compose up -d --build`. Keep the same connection hostname, or export a replacement connection file on a replacement phone. Never extract over a running vault.

## Verification

`python3 -m unittest discover -s server/remote-vault -v` from the repository root tests authentication, integrity, retries, quotas, crash cleanup, recovery envelope immutability and exact trash expiry. `bash tools/full-harness.sh` also runs the app unit suite, connected Compose UI tests and the R8 publication build. A running Android emulator/device is required. GitHub CI and release both gate their builds on server and emulator checks. UI screenshots are attached to the workflow run.
