// This module has no Java sources; it packages the runnable server stack:
//   docker/deephaven/  - custom Deephaven image (OIDC provider jar + Keycloak JS auth plugin + orders app)
//   docker/keycloak/   - Keycloak realm import with demo users, roles, and clients
//   compose.yaml       - podman/docker compose stack wiring the two together
// Bring it up with: podman compose -f deephaven-keycloak-oidc-server/compose.yaml up --build
plugins {
    base
}
