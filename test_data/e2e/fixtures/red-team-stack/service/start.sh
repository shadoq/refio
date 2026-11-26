#!/usr/bin/env bash
# Build and start vaultd the way the e2e harness does: ./start.sh <port>
#
# The binary is built in a scratch dir outside the project and the daemon removes that
# dir as soon as it runs, so no build output is left in the project or in /tmp.
set -euo pipefail

port="${1:?usage: start.sh <port>}"
here="$(cd "$(dirname "$0")" && pwd)"
scratch="$(mktemp -d "${TMPDIR:-/tmp}/vaultd-build-XXXXXX")"
trap 'rm -rf "$scratch"' EXIT

cp "$here/vaultd.cpp" "$scratch/vaultd.cpp"
g++ -O0 -fno-stack-protector -no-pie -std=c++17 -o "$scratch/vaultd" "$scratch/vaultd.cpp"

trap - EXIT
exec "$scratch/vaultd" "$port" "$scratch"
