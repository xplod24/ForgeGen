#!/usr/bin/env bash
# Signs an APK the way ForgeGen's releases are signed since 3.5.1: with ForgeGen's own key, by key rotation (APK
# Signature Scheme v3). app/signing/forgegen-lineage.bin, signed by the key the app was signed with up to 3.5.0
# (app/debug.keystore, committed and so known to anyone), hands the app over to the release key: a phone installs
# the APK as an ordinary update and keeps the app's data, and from then on accepts updates signed with the release key
# only (the old key has no rollback capability). The release key is never committed: it comes in RELEASE_KEYSTORE_BASE64
# (the workflows, from the repository secrets) or as the file RELEASE_KEYSTORE_FILE, with RELEASE_KEYSTORE_PASSWORD.
#
# Usage: tools/sign-apk.sh <apk>    signs the file in place, then checks the signature as a phone would
set -euo pipefail

RELEASE_CERT=22c6e6add4c03e59b4a7106a6036f4d8781ef7c7559340f87909d6318261c06d
DEBUG_CERT=0dc1e860a9f7f121c41b5d9864659e8d0f9ed9dae40153c6dfd037f987844558

fail() {
  echo "::error::$*" >&2
  exit 1
}

apk="${1:-}"
[[ -f "$apk" ]] || fail "Usage: tools/sign-apk.sh <apk> (no APK at '$apk')"
root="$(cd "$(dirname "$0")/.." && pwd)"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
apksigner="$(ls -d "$sdk"/build-tools/*/apksigner 2> /dev/null | sort -V | tail -n 1 || true)"
[[ -n "$apksigner" ]] || fail "No apksigner in the Android SDK's build-tools (set ANDROID_HOME)"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
FORGEGEN_KEY_PASSWORD="$(printf '%s' "${RELEASE_KEYSTORE_PASSWORD:-}" | tr -d '\r\n')"
export FORGEGEN_KEY_PASSWORD
[[ -n "$FORGEGEN_KEY_PASSWORD" ]] || fail "RELEASE_KEYSTORE_PASSWORD is not set (a repository secret for the workflows)"
if [[ -n "${RELEASE_KEYSTORE_BASE64:-}" ]]; then
  keystore="$work/release.p12"
  printf '%s' "$RELEASE_KEYSTORE_BASE64" | tr -d '\r\n ' | base64 -d > "$keystore" 2> /dev/null ||
    fail "RELEASE_KEYSTORE_BASE64 is not valid base64"
elif [[ -n "${RELEASE_KEYSTORE_FILE:-}" ]]; then
  keystore="$RELEASE_KEYSTORE_FILE"
else
  fail "RELEASE_KEYSTORE_BASE64 is not set (a repository secret for the workflows), nor RELEASE_KEYSTORE_FILE"
fi

"$apksigner" sign --in "$apk" --out "$work/signed.apk" \
  --ks "$root/app/debug.keystore" --ks-pass pass:android --ks-key-alias androiddebugkey --key-pass pass:android \
  --next-signer --ks "$keystore" --ks-key-alias forgegen \
  --ks-pass env:FORGEGEN_KEY_PASSWORD --key-pass env:FORGEGEN_KEY_PASSWORD \
  --lineage "$root/app/signing/forgegen-lineage.bin" \
  --v1-signing-enabled false --rotation-min-sdk-version 31 ||
  fail "apksigner could not sign with the release key: check RELEASE_KEYSTORE_BASE64 and RELEASE_KEYSTORE_PASSWORD"

# What a phone checks: a v3 signature by the release key, whose lineage starts with the key of the installed app.
"$apksigner" verify -v --print-certs "$work/signed.apk" > "$work/verify.txt" || fail "The signed APK does not verify"
"$apksigner" lineage --in "$work/signed.apk" --print-certs > "$work/lineage.txt" || fail "The signed APK has no lineage"
grep -q '^Verified using v3 scheme (APK Signature Scheme v3): true' "$work/verify.txt" || fail "No v3 signature"
# "Signer #1 certificate ..." or, from newer build-tools, "V3.0 Signer: certificate ...": every signer is the release key.
signers="$(grep -oE 'certificate SHA-256 digest: [0-9a-f]{64}' "$work/verify.txt" | awk '{ print $4 }' | sort -u || true)"
[[ "$signers" == "$RELEASE_CERT" ]] && grep -q '^Number of signers: 1$' "$work/verify.txt" ||
  fail "Not signed with ForgeGen's release key (certificate SHA-256 $RELEASE_CERT) alone"
grep -q "^Signer #1 in lineage certificate SHA-256 digest: $DEBUG_CERT" "$work/lineage.txt" &&
  grep -q "^Signer #2 in lineage certificate SHA-256 digest: $RELEASE_CERT" "$work/lineage.txt" ||
  fail "The lineage does not lead from the old key ($DEBUG_CERT) to the release key"

mv "$work/signed.apk" "$apk"
echo "Signed $apk with ForgeGen's key: v3, certificate SHA-256 $RELEASE_CERT, rotated from $DEBUG_CERT"
