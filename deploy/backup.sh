#!/usr/bin/env bash
set -Eeuo pipefail

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
env_file="$repo_root/.env.production"
output_dir="$repo_root/backups"
RETENTION_DAYS=${RETENTION_DAYS:-7}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --env-file) env_file=$2; shift 2 ;;
    --output-dir) output_dir=$2; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

[[ -f "$env_file" ]] || { echo "environment file not found" >&2; exit 2; }
mkdir -p "$output_dir"
output_dir=$(CDPATH= cd -- "$output_dir" && pwd -P)
[[ "$output_dir" != "/" && ${#output_dir} -gt 3 ]] || { echo "unsafe backup directory" >&2; exit 2; }

set -a
# shellcheck disable=SC1090
source "$env_file"
set +a

compose=(docker compose --env-file "$env_file" -f "$repo_root/docker-compose.prod.yml")
running=$("${compose[@]}" ps --status running --services)
grep -qx postgres <<<"$running" || { echo "postgres is not running" >&2; exit 1; }
grep -qx minio <<<"$running" || { echo "minio is not running" >&2; exit 1; }

timestamp=$(date -u +%Y%m%dT%H%M%SZ)
db_tmp=$(mktemp "$output_dir/.postgres-${timestamp}.XXXXXX")
trap 'rm -f "$db_tmp"' EXIT
"${compose[@]}" exec -T postgres \
  pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc >"$db_tmp"
mv "$db_tmp" "$output_dir/postgres-${timestamp}.dump"

minio_dir="$output_dir/minio-${timestamp}"
mkdir -p "$minio_dir"
docker run --rm \
  --network ai-interview-prod_backend \
  -v "$minio_dir:/backup" \
  -e MINIO_ROOT_USER \
  -e MINIO_ROOT_PASSWORD \
  -e APP_STORAGE_BUCKET \
  --entrypoint /bin/sh \
  minio/mc:latest -ec '
    mc alias set local http://minio:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"
    mc mirror --overwrite "local/$APP_STORAGE_BUCKET" /backup
  '

find "$output_dir" -maxdepth 1 -type f -name 'postgres-*.dump' -mtime "+$RETENTION_DAYS" -delete
find "$output_dir" -maxdepth 1 -type d -name 'minio-*' -mtime "+$RETENTION_DAYS" -exec rm -rf -- {} +

echo "backup completed: $timestamp"
