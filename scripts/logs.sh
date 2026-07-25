#!/usr/bin/env bash
# Follows logs from the demo stack.
# Usage: scripts/logs.sh [deephaven|keycloak]   (omit the argument for both services)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="$REPO_ROOT/deephaven-keycloak-oidc-server/compose.yaml"

if [[ $# -gt 0 ]]; then
  podman compose -f "$COMPOSE_FILE" logs -f "$1"
else
  podman compose -f "$COMPOSE_FILE" logs -f
fi
