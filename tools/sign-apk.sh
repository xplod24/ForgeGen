#!/usr/bin/env bash
# Signs an APK the way ForgeGen's releases are signed: with ForgeGen's own key alone (APK Signature Scheme v3), then
# checks the signature as a phone would. Since 3.5.2-2 the app's package is io.github.xplod24.forgegen, a new app with
# no history, so the signature carries no lineage and the old debug key (app/debug.keystore, public) has no say in it.
# (3.5.1 to 3.5.2-1, still io.github.xplod24.forgegen.debug, were signed by rotation from that debug key.) The release
# key is never committed: it comes in RELEASE_KEYSTORE_BASE64 (the workflows, from the repository secrets) or as the
# file RELEASE_KEYSTORE_FILE, with RELEASE_KEYSTORE_PASSWORD.
#
# Usage: tools/sign-apk.sh <apk>    signs the file in place, then checks the signature
set -euo pipefail

RELEASE_CERT=22c6e6add4c03e59b4a7106a6036f4d8781ef7c7559340f87909d6318261c06d

fail() {
  echo "::error::$*" >&2
  exit 1
}

apk="${1:-}"
[[ -f "$apk" ]] || fail "Usage: tools/sign-apk.sh <apk> (no APK at '$apk')"
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

# Replaces the build's own signature (Gradle signs with the debug key unless RELEASE_KEYSTORE_FILE is set).
"$apksigner" sign --in "$apk" --out "$work/signed.apk" \
  --ks "$keystore" --ks-key-alias forgegen --ks-pass env:FORGEGEN_KEY_PASSWORD --key-pass env:FORGEGEN_KEY_PASSWORD \
  --v1-signing-enabled false ||
  fail "apksigner could not sign with the release key: check RELEASE_KEYSTORE_BASE64 and RELEASE_KEYSTORE_PASSWORD"

"$apksigner" verify -v --print-certs "$work/signed.apk" > "$work/verify.txt" || fail "The signed APK does not verify"
grep -q '^Verified using v3 scheme (APK Signature Scheme v3): true' "$work/verify.txt" || fail "No v3 signature"
# "Signer #1 certificate ..." or, from newer build-tools, "V3.0 Signer: certificate ...": every signer is the release key.
signers="$(grep -oE 'certificate SHA-256 digest: [0-9a-f]{64}' "$work/verify.txt" | awk '{ print $4 }' | sort -u || true)"
[[ "$signers" == "$RELEASE_CERT" ]] && grep -q '^Number of signers: 1$' "$work/verify.txt" ||
  fail "Not signed with ForgeGen's release key (certificate SHA-256 $RELEASE_CERT) alone"
if "$apksigner" lineage --in "$work/signed.apk" > /dev/null 2>&1; then
  fail "The signed APK carries a signing lineage: the new package must trust the release key alone"
fi

mv "$work/signed.apk" "$apk"
echo "Signed $apk with ForgeGen's key: v3, certificate SHA-256 $RELEASE_CERT"
