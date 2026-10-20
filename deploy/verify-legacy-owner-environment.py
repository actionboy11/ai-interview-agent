#!/usr/bin/env python3
"""Read-only Compose 5 identity preflight; never print rendered environment values."""

import argparse
import getpass
import json
import os
from pathlib import Path
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", required=True)
    parser.add_argument("--expected-username-stdin", action="store_true",
                        help="read the expected literal username from stdin instead of a hidden prompt")
    args = parser.parse_args()
    expected = (sys.stdin.readline().rstrip("\r\n") if args.expected_username_stdin
                else getpass.getpass("Legacy owner literal username (not a password): "))
    if not expected.strip():
        raise SystemExit("legacy owner must be nonempty for this upgrade")

    process_environment = os.environ.copy()
    for variable in ("APP_LEGACY_OWNER_USERNAME", "APP_AUTH_BOOTSTRAP_USERNAME",
                     "APP_AUTH_BOOTSTRAP_PASSWORD", "APP_AUTH_BOOTSTRAP_DISPLAY_NAME"):
        process_environment.pop(variable, None)
    root = Path(__file__).resolve().parent.parent
    try:
        version = subprocess.run(["docker", "compose", "version", "--short"],
                                 env=process_environment, capture_output=True, text=True,
                                 encoding="utf-8", timeout=30)
        if version.returncode or not version.stdout.strip().lstrip("v").startswith("5."):
            raise SystemExit("this production preflight requires Docker Compose 5")
        rendered = subprocess.run([
            "docker", "compose", "--env-file", str(Path(args.env_file).resolve()),
            "-f", str(root / "docker-compose.prod.yml"), "config", "--format", "json",
        ], env=process_environment, capture_output=True, text=True, encoding="utf-8", timeout=30)
        if rendered.returncode:
            raise SystemExit("cannot render production Compose configuration")
        environment = json.loads(rendered.stdout)["services"]["app"]["environment"]
    except (OSError, UnicodeError, subprocess.TimeoutExpired, ValueError, KeyError, TypeError):
        raise SystemExit("cannot render production Compose configuration") from None

    # Compose 5 config doubles every dollar AFTER JSON marshaling for re-serialization.
    # Undo that one output layer; do not expand or otherwise interpret the username.
    # https://github.com/docker/compose/blob/v5.1.4/cmd/compose/config.go#L178-L180
    owner = environment.get("APP_LEGACY_OWNER_USERNAME")
    bootstrap = environment.get("APP_AUTH_BOOTSTRAP_USERNAME", "")
    if not isinstance(owner, str) or not isinstance(bootstrap, str):
        raise SystemExit("legacy-owner/bootstrap username mismatch")
    owner = owner.replace("$$", "$")
    bootstrap = bootstrap.replace("$$", "$")
    if owner != expected or (bootstrap and bootstrap != expected):
        raise SystemExit("legacy-owner/bootstrap username mismatch")
    print("literal legacy-owner environment preflight passed")


if __name__ == "__main__":
    main()
