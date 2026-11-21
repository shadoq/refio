#!/bin/sh
# restore-history.sh - materialise the packed git history into this working tree.
# The incident export packed .git into history.git.bundle; this script restores it
# (idempotent: a no-op once .git exists).
set -e

if [ -d .git ]; then
    echo "history already restored at $(git rev-parse --short HEAD)"
    exit 0
fi

git init -q
git fetch -q history.git.bundle main
git reset -q --mixed FETCH_HEAD
echo "history restored on branch $(git branch --show-current) at $(git rev-parse --short HEAD)"
