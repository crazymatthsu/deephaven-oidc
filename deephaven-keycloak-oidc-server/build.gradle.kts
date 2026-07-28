// Keycloak-based OIDC stack for Deephaven (no Java sources — packaging/deploy module):
//   - docker/deephaven/  - custom Deephaven image (published OIDC provider jar + Keycloak JS auth
//                          plugin + orders app)
//   - docker/keycloak/   - Keycloak realm import with demo users, roles, and clients
//   - compose.yaml       - podman/docker compose stack wiring the two together
//
// Bring the stack up with:
//   scripts/start.sh            # or: podman compose -f deephaven-keycloak-oidc-server/compose.yaml up --build
//
// The direct Entra ID implementation lives in the sibling module deephaven-entra-oidc-server.
