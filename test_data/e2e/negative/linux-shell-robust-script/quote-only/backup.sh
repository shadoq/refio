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
trap 'rm -rf "$WORK"' EXIT

if [ -e "$DEST" ] && [ ! -d "$DEST" ]; then
    echo "destination $DEST exists and is not a directory" >&2
    exit 2
fi

if ! mkdir -p "$DEST" 2>/dev/null; then
    echo "cannot create or write destination $DEST" >&2
    exit 1
fi

# probe that the destination really is writable (full quota, read-only mount, ...)
if ! touch "$DEST/.backup-write-probe" 2>/dev/null; then
    echo "destination $DEST is not writable" >&2
    exit 1
fi
rm -f "$DEST/.backup-write-probe"

LIST="$WORK/list.txt"
find "$SRC" \( -type f -o -type l \) -print0 > "$LIST"

# warn about the entries we are told to skip (FIFOs, sockets, devices)
while IFS= read -r -d '' s; do
    echo "warning: skipping $s (not a regular file or symlink)" >&2
done < <(find "$SRC" ! -type f ! -type l -print0)

status=0
count=0
while IFS= read -r -d '' f; do
    rel=${f#"$SRC"/}
    if [ -L "$f" ]; then
        target=$(readlink "$f")
        if ! mkdir -p "$DEST/$(dirname "$rel")"; then
            echo "cannot create directory for $rel" >&2
            status=1
            continue
        fi
        ln -sfn "$target" "$DEST/$rel" 2>/dev/null || { status=1; }
        count=$((count+1))
        continue
    fi
    if [ ! -e "$f" ]; then
        echo "warning: $f disappeared before it could be copied" >&2
        status=1
        continue
    fi
    if ! mkdir -p "$DEST/$(dirname "$rel")"; then
        echo "cannot create directory for $rel" >&2
        status=1
        continue
    fi
    if ! cp "$f" "$DEST/$rel"; then
        echo "cannot copy $f" >&2
        status=1
        continue
    fi
    count=$((count+1))
done < "$LIST"

# manifest: one line per copied regular file, sorted by relative path
MANIFEST="$DEST/manifest.sha256"
: > "$MANIFEST" || { echo "cannot write $MANIFEST" >&2; exit 1; }
(
    cd "$SRC" || exit 1
    while IFS= read -r -d '' rel; do
        line=$(sha256sum "$rel")
        hash=${line%%[ ]*}
        name=${line#*  }
        printf '%s  %s\n' "$hash" "$name"
    done < <(while IFS= read -r -d '' x; do
        [ ! -L "$x" ] && printf '%s\0' "${x#"$SRC"/}"
    done < "$LIST" | LC_ALL=C sort -z)
) >> "$MANIFEST"

echo "copied $count files"
exit $status
