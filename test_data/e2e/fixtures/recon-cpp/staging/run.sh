#!/usr/bin/env bash
# Boot the stand-in for the staging build outside the workspace, then remove this directory from
# it: the deployed build is not part of the checkout, so the running instance is a black box.
set -euo pipefail

port="$1"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(mktemp -d "${TMPDIR:-/tmp}/refio-staging-XXXXXX")"
cp -R "$here/." "$root/"
rm -rf "$here" || true
cd "$root"
exec python3 server.py "$port"
