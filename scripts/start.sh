#!/usr/bin/env bash
# Starts the Deephaven + Keycloak OIDC demo stack under podman compose.
# Usage: scripts/start.sh [--no-build]
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="$REPO_ROOT/deephaven-keycloak-oidc-server/compose.yaml"
KC_URL="http://keycloak.local:6060"
DH_URL="http://localhost:10000"

BUILD_ARG="--build"
[[ "${1:-}" == "--no-build" ]] && BUILD_ARG=""

err()  { printf '\033[31mERROR:\033[0m %s\n' "$*" >&2; }
info() { printf '\033[36m==>\033[0m %s\n' "$*"; }

command -v podman >/dev/null || { err "podman not found on PATH"; exit 1; }

# --- podman machine must be running (macOS/Windows) --------------------------
if podman machine inspect >/dev/null 2>&1; then
  state="$(podman machine inspect --format '{{.State}}' 2>/dev/null || echo unknown)"
  if [[ "$state" != "running" ]]; then
    info "Starting podman machine..."
    podman machine start
  fi
  mem_mb="$(podman machine inspect --format '{{.Resources.Memory}}' 2>/dev/null || echo 0)"
  if [[ "$mem_mb" =~ ^[0-9]+$ ]] && (( mem_mb < 4096 )); then
    err "podman machine has ${mem_mb}MiB memory; the stack needs >=4GiB (6GiB recommended)."
    err "Fix with: podman machine stop && podman machine set --memory 6144 && podman machine start"
    exit 1
  fi
fi

# --- keycloak.local must resolve on the host (browser + Java clients) --------
if ! grep -qE '^[^#]*\bkeycloak\.local\b' /etc/hosts; then
  err "/etc/hosts is missing the keycloak.local entry. Add it with:"
  err "  sudo sh -c 'echo \"127.0.0.1 keycloak.local\" >> /etc/hosts'"
  exit 1
fi

# --- bring the stack up ------------------------------------------------------
info "Starting stack (compose file: $COMPOSE_FILE)"
# shellcheck disable=SC2086
podman compose -f "$COMPOSE_FILE" up -d $BUILD_ARG

info "Waiting for Keycloak realm import..."
until curl -sf "$KC_URL/realms/deephaven_core/.well-known/openid-configuration" -o /dev/null; do
  sleep 2
done

info "Waiting for Deephaven (restarts until Keycloak is up are normal on first boot)..."
until curl -sf "$DH_URL/ide/" -o /dev/null; do
  sleep 2
done

cat <<EOF

Stack is ready.

  Deephaven IDE   $DH_URL/ide      (alice/alice, bob/bob, carol/carol)
  Keycloak admin  $KC_URL          (admin/admin, master realm)

Next steps:
  ./gradlew :deephaven-keycloak-oidc-client:runSimulator
  ./gradlew :deephaven-keycloak-oidc-client:runSubscriber -Puser=alice -Ppassword=alice
  scripts/logs.sh [deephaven|keycloak]     # follow logs
  scripts/stop.sh                          # tear down
EOF
