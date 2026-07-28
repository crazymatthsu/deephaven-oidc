#!/usr/bin/env bash
# Starts one of the two OIDC demo stacks under podman compose.
#
# Usage: scripts/start.sh [keycloak|entra] [--no-build]
#
#   keycloak (default)  Deephaven + local Keycloak IdP (web IDE login works; demo users alice/bob/carol)
#   entra               Deephaven only, validating tokens directly against Microsoft Entra ID.
#                       Requires ENTRA_TENANT_ID and ENTRA_AUDIENCE in the environment (or a .env file
#                       next to deephaven-entra-oidc-server/compose.yaml — see its .env.example).
#                       NOTE: no web IDE login yet in this mode; use the Java clients with
#                       AUTH_PROVIDER=entra (device-code sign-in with Microsoft Authenticator MFA).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

MODE="keycloak"
BUILD_ARG="--build"
for arg in "$@"; do
  case "$arg" in
    keycloak|entra) MODE="$arg" ;;
    --no-build) BUILD_ARG="" ;;
    *) echo "Usage: scripts/start.sh [keycloak|entra] [--no-build]" >&2; exit 2 ;;
  esac
done

KC_URL="http://keycloak.local:6060"
DH_URL="http://localhost:10000"

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

# =============================== entra mode ==================================
if [[ "$MODE" == "entra" ]]; then
  COMPOSE_FILE="$REPO_ROOT/deephaven-entra-oidc-server/compose.yaml"
  ENV_FILE="$REPO_ROOT/deephaven-entra-oidc-server/.env"

  # compose reads .env automatically; only insist on exported vars when there is no .env file.
  if [[ ! -f "$ENV_FILE" && ( -z "${ENTRA_TENANT_ID:-}" || -z "${ENTRA_AUDIENCE:-}" ) ]]; then
    err "Entra mode needs ENTRA_TENANT_ID and ENTRA_AUDIENCE."
    err "Export them, or copy deephaven-entra-oidc-server/.env.example to .env and fill it in."
    exit 1
  fi

  info "Building the Entra OIDC handler fat jar..."
  "$REPO_ROOT/gradlew" -p "$REPO_ROOT" :deephaven-entra-oidc-server:fatJar -q

  info "Starting Deephaven with direct Entra ID authentication (compose file: $COMPOSE_FILE)"
  # shellcheck disable=SC2086
  podman compose -f "$COMPOSE_FILE" up -d $BUILD_ARG

  info "Waiting for Deephaven..."
  until curl -sf "$DH_URL/ide/" -o /dev/null; do
    sleep 2
  done

  cat <<EOF

Entra stack is ready.

  Deephaven       $DH_URL   (gRPC/Barrage clients only — web IDE login for Entra is not built yet)
  Identity        Microsoft Entra ID (tenant \${ENTRA_TENANT_ID})

Next steps (AUTH_PROVIDER=entra; see deephaven-keycloak-oidc-client/README-ENTRA.md):
  export AUTH_PROVIDER=entra ENTRA_TENANT_ID=... ENTRA_CLIENT_ID=... ENTRA_SCOPE=...
  ./gradlew :deephaven-keycloak-oidc-client:runSimulator      # needs ENTRA_CLIENT_SECRET
  ./gradlew :deephaven-keycloak-oidc-client:runSubscriber     # device-code sign-in + Authenticator MFA
  scripts/logs.sh entra                    # follow logs
  scripts/stop.sh entra                    # tear down
EOF
  exit 0
fi

# ============================== keycloak mode ================================
COMPOSE_FILE="$REPO_ROOT/deephaven-keycloak-oidc-server/compose.yaml"

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
