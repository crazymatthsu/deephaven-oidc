# Entra ID client auth (MSAL)

When `AUTH_PROVIDER=entra`, the demo clients acquire tokens with **MSAL4J** instead of Keycloak.

## Environment

| Variable | Required | Purpose |
|----------|----------|---------|
| `AUTH_PROVIDER` | yes (`entra`) | Select Entra path |
| `ENTRA_TENANT_ID` | yes | Directory (tenant) ID |
| `ENTRA_CLIENT_ID` | yes | App registration client ID |
| `ENTRA_SCOPE` | yes | e.g. `api://<app-id>/.default` or `api://<app-id>/access_as_user` |
| `ENTRA_CLIENT_SECRET` | simulator only | Confidential client secret |
| `DH_HOST` / `DH_PORT` / `DH_TLS` | as needed | Deephaven endpoint |
| `DH_USER` / `DH_PASSWORD` | subscriber | Entra user for ROPC demo |

Server must run with `EntraOidcAuthenticationHandler` (see server module README-ENTRA).

## Run simulator (client credentials)

```bash
export AUTH_PROVIDER=entra
export ENTRA_TENANT_ID=...
export ENTRA_CLIENT_ID=...          # confidential app
export ENTRA_CLIENT_SECRET=...
export ENTRA_SCOPE="api://your-app-id/.default"

./gradlew :deephaven-keycloak-oidc-client:runSimulator
```

## Run subscriber (username/password — ROPC)

ROPC must be enabled for the public app and the tenant. Prefer device-code in production.

```bash
export AUTH_PROVIDER=entra
export ENTRA_TENANT_ID=...
export ENTRA_CLIENT_ID=...          # public app (or same app if ROPC allowed)
export ENTRA_SCOPE="api://your-app-id/access_as_user"

./gradlew :deephaven-keycloak-oidc-client:runSubscriber -Puser=alice -Ppassword='...'
```

Assign Entra **app roles** (or groups) named `trader-us`, `trader-emea`, or `dh-admin` so the subscriber can pick the entitled view.

## Keycloak remains the default

Omit `AUTH_PROVIDER` or set `AUTH_PROVIDER=keycloak` to keep the original local compose behaviour.
