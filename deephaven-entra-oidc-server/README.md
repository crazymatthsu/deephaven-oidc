# deephaven-entra-oidc-server — direct Microsoft Entra ID auth for Deephaven

This module is the **direct Entra ID** implementation (no Keycloak): a custom Deephaven
`AuthenticationRequestHandler` that validates Entra-issued access tokens against the tenant's JWKS,
packaged as a self-contained fat jar and wired into its own compose stack.

The Keycloak implementation lives in the sibling module
[`deephaven-keycloak-oidc-server`](../deephaven-keycloak-oidc-server); switch between the two with
`scripts/start.sh [keycloak|entra]` (server side) and `AUTH_PROVIDER=keycloak|entra` (Java clients).

## Status

| Surface | Status |
| --- | --- |
| gRPC / Barrage / Flight (Java clients) | ✅ implemented (this module + MSAL flows in the clients) |
| User MFA (Microsoft Authenticator) | ✅ device-code and interactive browser flows in the clients |
| Web IDE (browser) login | ✅ implemented — [`js-plugin-auth-entra`](../js-plugin-auth-entra) (MSAL.js auth-code+PKCE, enterprise SSO + Authenticator MFA), baked into this image; smoke-verified against a mock issuer, live tenant test pending |
| Per-user identity / roles on the server | ✅ `EntraUserAuthContext` carries `oid`/username/roles; only `ENTRA_SUPERUSER_ROLES` (default `dh-admin`) become SuperUser; unit-tested against a mock issuer |

## Contents

- `src/main/java/io/deephaven/oidc/entra/EntraOidcAuthenticationHandler.java` — Spring Security
  `NimbusJwtDecoder`-based handler (issuer + optional audience validation)
- `docker/deephaven/Dockerfile` — Deephaven server image with the fat jar + orders demo app
  (builds with the **repo root** as context to reach Gradle output and the shared `app.d/`)
- `compose.yaml` — single-service stack (Entra ID is the IdP; no local identity container)
- `.env.example` — required environment (`ENTRA_TENANT_ID`, `ENTRA_AUDIENCE`)

## Build

```bash
./gradlew :deephaven-entra-oidc-server:fatJar
```

Output: `build/libs/deephaven-entra-oidc-auth-all.jar` — the handler **plus** Spring Security and
Nimbus, one file for the server's `EXTRA_CLASSPATH` (no slf4j binding bundled; the Deephaven server
brings its own logging backend).

## Run the stack

```bash
cp deephaven-entra-oidc-server/.env.example deephaven-entra-oidc-server/.env   # then edit
scripts/start.sh entra
```

Or manually:

```bash
./gradlew :deephaven-entra-oidc-server:fatJar
ENTRA_TENANT_ID=<tenant-id> ENTRA_AUDIENCE=api://<app-id-uri> \
  podman compose -f deephaven-entra-oidc-server/compose.yaml up --build
```

## Enable on any Deephaven server (without the compose stack)

```text
EXTRA_CLASSPATH=/path/to/deephaven-entra-oidc-auth-all.jar

START_OPTS="
  -DAuthHandlers=io.deephaven.oidc.entra.EntraOidcAuthenticationHandler
  -Dauthentication.oidc.entra.issuer-uri=https://login.microsoftonline.com/<tenant-id>/v2.0
  -Dauthentication.oidc.entra.audience=api://<your-app-id-uri>
  -Dauthentication.oidc.entra.superuser-roles=dh-admin        # optional; default dh-admin
  -Dauthentication.client.configuration.list=AuthHandlers
"
```

Identity mapping: tokens holding one of the `superuser-roles` claims become Deephaven
`SuperUser`; every other valid token is admitted as `EntraUserAuthContext` carrying the
principal's `oid`, username (`preferred_username`/`upn`, or the app id for service principals),
and `roles`/`groups` claims. Each login is logged as `Entra login: user=... roles=[...]`.
Note: Community's default authorization permits all operations regardless of context — the
context is for auditing and future custom `AuthorizationProvider` wiring; script-level RLS
remains the enforcement point.

Environment variable equivalents: `AUTHENTICATION_OIDC_ENTRA_ISSUER_URI`,
`AUTHENTICATION_OIDC_ENTRA_AUDIENCE`.

## Client usage

```java
SessionConfig.builder()
    .authenticationTypeAndValue(
        "io.deephaven.oidc.entra.EntraOidcAuthenticationHandler " + accessToken)
    .build();
```

Acquire `accessToken` with MSAL — see
[`deephaven-keycloak-oidc-client/README-ENTRA.md`](../deephaven-keycloak-oidc-client/README-ENTRA.md)
for the demo clients: client-credentials for the simulator, **device-code with Microsoft
Authenticator MFA** (default) for users.

## Design notes & roadmap

- [`docs/oidc/custom-entra-oidc-handler-with-msal.md`](../docs/oidc/custom-entra-oidc-handler-with-msal.md) — feasibility research
- [`docs/oidc/ENTRA-IMPLEMENTATION-PLAN.md`](../docs/oidc/ENTRA-IMPLEMENTATION-PLAN.md) — detailed plan for the unimplemented phases
