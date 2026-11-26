#!/usr/bin/env bash
# Boot the incident supervisor outside the workspace, then remove this directory from
# it: the "production host" the agent triages is not part of the checkout, so the live
# processes are a black box that has to be examined through /proc and ps.
set -euo pipefail

port="$1"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(mktemp -d "${TMPDIR:-/tmp}/refio-triage-XXXXXX")"
cp -R "$here/." "$root/"
rm -rf "$here" || true
cd "$root"
exec python3 supervisor.py "$port"
