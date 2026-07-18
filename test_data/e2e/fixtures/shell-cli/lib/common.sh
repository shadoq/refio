#!/usr/bin/env bash
# Shared helpers sourced by the deploy script.

# require_env NAME: abort if the named environment variable is unset or empty.
require_env() {
  local name="$1"
  if [[ -z "${!name:-}" ]]; then
    echo "missing required env: $name" >&2
    exit 1
  fi
}

# log LEVEL MESSAGE: timestamped log line to stderr.
log() {
  local level="$1"; shift
  echo "$(date +%H:%M:%S) [$level] $*" >&2
}
