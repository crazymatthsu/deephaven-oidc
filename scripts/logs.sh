#!/usr/bin/env bash
# Follows logs from a demo stack.
# Usage: scripts/logs.sh [keycloak|entra] [service]
#   scripts/logs.sh                      # keycloak stack, both services
#   scripts/logs.sh deephaven            # keycloak stack, deephaven service only
#   scripts/logs.sh keycloak keycloak    # keycloak stack, keycloak service only
#   scripts/logs.sh entra                # entra stack (single deephaven service)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

MODE="keycloak"
SERVICE=""
case "${1:-}" in
  entra) MODE="entra"; SERVICE="${2:-}" ;;
  keycloak)
    # `scripts/logs.sh keycloak` historically meant "the keycloak service"; a second argument
    # selects the service explicitly.
    if [[ $# -gt 1 ]]; then SERVICE="$2"; else SERVICE="keycloak"; fi ;;
  "") ;;
  *) SERVICE="$1" ;;
esac

case "$MODE" in
  keycloak) COMPOSE_FILE="$REPO_ROOT/deephaven-keycloak-oidc-server/compose.yaml" ;;
  entra)    COMPOSE_FILE="$REPO_ROOT/deephaven-entra-oidc-server/compose.yaml" ;;
esac

# Placeholders keep the entra compose file's required-var interpolation happy for read-only ops.
export ENTRA_TENANT_ID="${ENTRA_TENANT_ID:-unset}" ENTRA_AUDIENCE="${ENTRA_AUDIENCE:-unset}"

if [[ -n "$SERVICE" ]]; then
  podman compose -f "$COMPOSE_FILE" logs -f "$SERVICE"
else
  podman compose -f "$COMPOSE_FILE" logs -f
fi
