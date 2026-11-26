#!/usr/bin/env bash
#
# backup.sh - copy a source directory into a destination directory and record a manifest.
#
# Usage: bash backup.sh <source-dir> <dest-dir>
#
set -u

SRC=$1
DEST=$2

WORK=$(mktemp -d /tmp/backup.XXXXXX)

# collect the files to copy (plain files and symlinks only)
LIST=$WORK/list.txt
find $SRC -type f -o -type l > $LIST

# make sure the destination exists
test -d $DEST || echo "destination $DEST does not exist" >&2

count=0
for f in $(cat $LIST); do
    rel=${f#$SRC/}
    mkdir -p $DEST/$(dirname $rel)
    if [ -L $f ]; then
        cp -P $f $DEST/$rel
    else
        cp $f $DEST/$rel
    fi
    count=$((count+1))
done

# a leftover temp file from a previous run, if any, is removed
if [ -f $WORK/old.tmp ]; then
    rm $WORK/old.tmp
fi

# write the manifest
sha256sum $DEST/* > $DEST/manifest.sha256 2>/dev/null

if [ $count -eq 0 ]; then
    echo "nothing to copy"
fi

echo "copied $count files"
