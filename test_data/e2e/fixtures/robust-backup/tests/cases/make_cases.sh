#!/usr/bin/env bash
# Generate the tricky regression tree used while developing backup.sh.
# Usage: bash make_cases.sh <target-dir>
set -euo pipefail

root="$1"
mkdir -p "$root/empty dir"
mkdir -p "$root/plain"
printf 'hello\n' > "$root/plain/hello.txt"
printf 'with spaces\n' > "$root/Quarterly Report Final.txt"
printf 'tab\tseparated\n' > "$root/plain/tab	name.txt"
printf 'newline\nname\n' > "$root/plain/new
line.txt"
printf 'globby\n' > "$root/plain/STAR*name?.txt[1].dat"
printf '\xc3\xa9\xc3\xa0\xc3\xbc unicode\n' > "$root/plain/Uni\xe9code N\xe4me.txt"
printf '' > "$root/plain/zero-byte.bin"
printf 'leading dash\n' > "$root/plain/-rf"
printf 'starts with dash\n' > "$root/plain/-not-a-flag"
ln -s "plain/hello.txt" "$root/link-to-hello"
ln -s "nowhere-at-all" "$root/dangling-link"
ln -s "plain" "$root/link-to-dir"
mkdir -p "$root/deep/a/b/c/d/e/f/g/h"
printf 'deep\n' > "$root/deep/a/b/c/d/e/f/g/h/leaf.txt"
mkfifo "$root/a-fifo" 2>/dev/null || true
