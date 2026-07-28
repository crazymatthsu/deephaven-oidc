# @deephaven-oidc/js-plugin-auth-entra

Deephaven web IDE login plugin for **Microsoft Entra ID** — the browser counterpart of
[`EntraOidcAuthenticationHandler`](../deephaven-entra-oidc-server). Auth-code + PKCE via MSAL.js:
enterprise SSO (silent sign-on for users already signed into their corporate account) and
**Microsoft Authenticator MFA** per your Conditional Access policy, then the access token is handed
to Deephaven with `client.login({ type: 'io.deephaven.oidc.entra.EntraOidcAuthenticationHandler', token })`.

## How it's built & shipped

- `npm run build` → single self-contained CommonJS `dist/index.js` (MSAL.js bundled; only
  `react`, `@deephaven/auth-plugins`, `@deephaven/jsapi-components` left external — the modules the
  web UI's plugin shim provides). Mirrors the published `@deephaven/js-plugin-auth-keycloak` shape.
- `@deephaven/log` is deliberately **not** used — its shim interop is what broke the Keycloak
  plugin on current web UIs (see [docs/auth-keycloak-js-plugin-fix.md](../docs/auth-keycloak-js-plugin-fix.md)).
- You don't build this by hand: the [Entra Dockerfile](../deephaven-entra-oidc-server/docker/deephaven/Dockerfile)
  builds it in a `node` stage and installs it under `/opt/deephaven/config/js-plugins/` with the manifest.

## Server configuration consumed (exposed via `authentication.client.configuration.list`)

| Property | Compose env | Meaning |
| --- | --- | --- |
| `authentication.oidc.entra.tenant-id` | `ENTRA_TENANT_ID` | Directory (tenant) ID |
| `authentication.oidc.entra.spa-client-id` | `ENTRA_SPA_CLIENT_ID` | SPA app registration client ID |
| `authentication.oidc.entra.scope` | `ENTRA_WEB_SCOPE` | Delegated scope, e.g. `api://<app-id>/access_as_user` |

If the SPA values are unset the plugin loads but reports the missing config in the login screen —
gRPC-only use of the Entra stack stays possible.

## Entra portal prerequisites

On the app registration, add a **Single-page application** platform whose redirect URI exactly
matches the IDE URL (`http://localhost:10000/ide/` locally; your HTTPS URL in production — Entra
only permits plain `http` for localhost), with delegated permission to the API scope. Details:
[ENTRA-IMPLEMENTATION-PLAN.md](../docs/oidc/ENTRA-IMPLEMENTATION-PLAN.md).
