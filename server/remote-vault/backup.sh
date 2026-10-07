#!/bin/sh
set -eu
umask 077
cd "$(dirname "$0")"
mkdir -p backups
docker compose stop vault
trap 'docker compose start vault >/dev/null' EXIT
# Purge expired objects before taking a snapshot. It contains no phone decryption keys.
python3 -c 'from server import Store; s=Store("data"); s.purge(); s.db.close()'
archive="backups/vault-$(date -u +%Y%m%dT%H%M%SZ).tar.gz"
tar -czf "$archive" data tls compose.yaml Caddyfile Dockerfile Dockerfile.https server.py provision.py bootstrap-token .env
sha256sum "$archive" > "$archive.sha256"
echo "$archive"
