#!/usr/bin/env bash
set -Eeuo pipefail

repo_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
env_file=${1:-}

if [[ -z "$env_file" || ! -f "$env_file" ]]; then
  echo "usage: $0 ENV_FILE" >&2
  exit 2
fi

rendered=$(mktemp)
trap 'rm -f "$rendered"' EXIT

docker compose \
  --env-file "$env_file" \
  -f "$repo_root/docker-compose.prod.yml" \
  config --format json >"$rendered"

if python3 -c 'import sys' >/dev/null 2>&1; then
  python_cmd=python3
elif python -c 'import sys' >/dev/null 2>&1; then
  python_cmd=python
else
  echo "Python 3 is required to validate the rendered Compose configuration" >&2
  exit 2
fi

"$python_cmd" - "$rendered" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    config = json.load(handle)

services = config.get("services", {})
expected = {
    "app", "caddy", "createbuckets", "frontend", "minio",
    "postgres", "rabbitmq", "redis",
}
if set(services) != expected:
    raise SystemExit("unexpected production service set")

for name, service in services.items():
    ports = service.get("ports") or []
    if name != "caddy" and ports:
        raise SystemExit(f"{name} must not publish host ports")
    if name == "caddy":
        targets = {int(port["target"]) for port in ports}
        published = {int(port["published"]) for port in ports}
        if targets != {80, 443} or published != {80, 443}:
            raise SystemExit("caddy may publish only 80 and 443")

app_env = services["app"].get("environment", {})
providers = (
    "APP_RESUME_MESSAGING_PROVIDER",
    "APP_VOICE_EVALUATION_MESSAGING_PROVIDER",
    "APP_INTERVIEW_EVALUATION_MESSAGING_PROVIDER",
    "APP_KNOWLEDGE_VECTORIZATION_MESSAGING_PROVIDER",
)
if any(app_env.get(name) != "rabbitmq" for name in providers):
    raise SystemExit("all asynchronous modules must use rabbitmq")
if not app_env.get("JAVA_TOOL_OPTIONS"):
    raise SystemExit("JAVA_TOOL_OPTIONS is required")

caddy_env = services["caddy"].get("environment", {})
for name in ("SITE_ADDRESS", "DEMO_USERNAME", "DEMO_PASSWORD_HASH"):
    if not caddy_env.get(name):
        raise SystemExit(f"caddy environment missing {name}")

required_secrets = {
    "POSTGRES_PASSWORD": services["postgres"].get("environment", {}).get("POSTGRES_PASSWORD"),
    "RABBITMQ_PASSWORD": services["rabbitmq"].get("environment", {}).get("RABBITMQ_DEFAULT_PASS"),
    "MINIO_ROOT_PASSWORD": services["minio"].get("environment", {}).get("MINIO_ROOT_PASSWORD"),
    "APP_AI_CONFIG_ENCRYPTION_KEY": app_env.get("APP_AI_CONFIG_ENCRYPTION_KEY"),
    "AI_BAILIAN_API_KEY": app_env.get("AI_BAILIAN_API_KEY"),
}
for name, value in required_secrets.items():
    normalized = str(value or "").lower()
    if not normalized or any(marker in normalized for marker in ("change_me", "password", "minioadmin")):
        raise SystemExit(f"{name} is missing or uses an unsafe placeholder")

print("production configuration validation passed")
PY
