#!/usr/bin/env bash
# Stops and removes the Deephaven + Keycloak demo stack.
# Note: state is ephemeral by design — Keycloak's dev DB and all published orders are discarded;
# the realm is re-imported and the orders app re-seeds on the next start.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
podman compose -f "$REPO_ROOT/deephaven-keycloak-oidc-server/compose.yaml" down
echo "Stack stopped."
