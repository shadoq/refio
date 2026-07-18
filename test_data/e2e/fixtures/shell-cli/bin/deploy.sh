#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "$0")/../lib/common.sh"

# build_artifact: package the current source tree into a tarball, echo its path.
build_artifact() {
  local out="/tmp/app-$(date +%s).tar.gz"
  tar -czf "$out" .
  echo "$out"
}

# push_artifact PATH: upload the artifact to the target host.
push_artifact() {
  local artifact="$1"
  require_env DEPLOY_HOST
  log INFO "pushing $artifact to $DEPLOY_HOST"
  scp "$artifact" "$DEPLOY_HOST:/srv/app/"
}

main() {
  require_env DEPLOY_HOST
  local artifact
  artifact="$(build_artifact)"
  push_artifact "$artifact"
  log INFO "done"
}

main "$@"
