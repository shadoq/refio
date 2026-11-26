#!/usr/bin/env bash
# Offline migration gate: bash + openssl only, no network.
# Exit 0 iff the migration state is correct: new chain verifies for sslserver, the old
# cert fails against the new trust anchor, the old cert fails -crl_check against the
# legacy CRL, and the proxy config references only the new files.
set -uo pipefail
cd "$(dirname "$0")"

fail() { echo "verify.sh: $*" >&2; exit 1; }

NEW_CA="pki/current/ca.crt"
NEW_CERT="pki/current/server.crt"
OLD_CA="pki/legacy/ca.crt"
OLD_CERT="pki/legacy/server.crt"
OLD_CRL="pki/legacy/crl.pem"
CONF="proxy/gw.conf"

# 1. the new chain verifies, with the sslserver purpose.
openssl verify -purpose sslserver -CAfile "$NEW_CA" "$NEW_CERT" >/dev/null 2>&1 \
  || fail "new server certificate does not verify against the new CA"

# 2. the new certificate really is signed by the new CA (not by the legacy one).
openssl verify -purpose sslserver -CAfile "$NEW_CA" -no_check_time "$NEW_CERT" >/dev/null 2>&1 \
  || fail "new certificate has a validity or trust problem"

# 3. the old certificate fails against the new trust anchor.
if openssl verify -purpose sslserver -CAfile "$NEW_CA" -no_check_time "$OLD_CERT" >/dev/null 2>&1; then
  fail "old certificate still verifies against the new CA (chain not replaced)"
fi

# 4. the old certificate still verifies against the legacy CA (stage 1 intact),
#    but fails -crl_check against the legacy CRL (it is revoked).
openssl verify -no_check_time -CAfile "$OLD_CA" "$OLD_CERT" >/dev/null 2>&1 \
  || fail "old certificate no longer verifies against the legacy CA (stage 1 damaged)"
openssl verify -crl_check -no_check_time -CRLfile "$OLD_CRL" -CAfile <(cat "$OLD_CA" "$OLD_CRL") "$OLD_CERT" >/dev/null 2>&1 \
  && fail "old certificate is NOT revoked by the legacy CRL"

# 5. the proxy config references only the new files.
grep -q "^cert *= *$NEW_CERT$" "$CONF" || fail "proxy cert does not point at the new certificate"
grep -q "^key *= *pki/server.key$" "$CONF" || fail "proxy key does not point at the new key"
if grep -q "pki/legacy" "$CONF"; then
  fail "proxy config still references the legacy PKI"
fi

echo "verify.sh: OK"
exit 0
