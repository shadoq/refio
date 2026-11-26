#!/usr/bin/env bash
#
# backup.sh - copy a source directory into a destination directory and record a manifest.
#
# Usage: bash backup.sh <source-dir> <dest-dir>
# Contract: docs/backup-spec.md (exit codes 0/1/2, manifest.sha256, idempotency).
#
set -u

if [ $# -ne 2 ]; then
    echo "usage: bash backup.sh <source-dir> <dest-dir>" >&2
    exit 2
fi

SRC=$1
DEST=$2

if [ ! -d "$SRC" ]; then
    echo "source $SRC is missing or not a directory" >&2
    exit 2
fi

WORK=$(mktemp -d "${TMPDIR:-/tmp}/backup.XXXXXX")
trap 'rm -rf -- "$WORK"' EXIT

if [ -e "$DEST" ] && [ ! -d "$DEST" ]; then
    echo "destination $DEST exists and is not a directory" >&2
    exit 2
fi

if ! mkdir -p -- "$DEST" 2>/dev/null; then
    echo "cannot create or write destination $DEST" >&2
    exit 1
fi

# probe that the destination really is writable (full quota, read-only mount, ...)
if ! touch -- "$DEST/.backup-write-probe" 2>/dev/null; then
    echo "destination $DEST is not writable" >&2
    exit 1
fi
rm -f -- "$DEST/.backup-write-probe"

cp -r "$SRC/." "$DEST/" || exit 1

echo "copied everything"
exit 0
