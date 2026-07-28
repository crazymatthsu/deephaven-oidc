#!/usr/bin/env bash
# Stops and removes a demo stack.
# Usage: scripts/stop.sh [keycloak|entra]   (default: keycloak)
# Note: state is ephemeral by design — Keycloak's dev DB and all published orders are discarded;
# the realm is re-imported and the orders app re-seeds on the next start.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODE="${1:-keycloak}"

case "$MODE" in
  keycloak) COMPOSE_FILE="$REPO_ROOT/deephaven-keycloak-oidc-server/compose.yaml" ;;
  entra)    COMPOSE_FILE="$REPO_ROOT/deephaven-entra-oidc-server/compose.yaml" ;;
  *) echo "Usage: scripts/stop.sh [keycloak|entra]" >&2; exit 2 ;;
esac

podman compose -f "$COMPOSE_FILE" down
echo "Stack stopped ($MODE)."
