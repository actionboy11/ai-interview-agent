#!/usr/bin/env bash
set -Eeuo pipefail

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
script="$repo_root/deploy/backup.sh"

[[ -f "$script" ]] || { echo "backup.sh is missing" >&2; exit 1; }

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

if bash "$script" --env-file "$tmp/missing.env" --output-dir "$tmp/backups" >/dev/null 2>&1; then
  echo "missing environment file must fail" >&2
  exit 1
fi

grep -q 'set -Eeuo pipefail' "$script"
grep -q 'mktemp' "$script"
grep -q 'trap.*rm -f' "$script"
grep -q 'pg_dump' "$script"
grep -q 'mc mirror' "$script"
grep -q -- '-e MINIO_ROOT_USER' "$script"
grep -q -- '-e MINIO_ROOT_PASSWORD' "$script"
grep -q -- '-e APP_STORAGE_BUCKET' "$script"
grep -q 'RETENTION_DAYS' "$script"
grep -q 'find.*maxdepth 1' "$script"

echo "backup safety checks passed"
