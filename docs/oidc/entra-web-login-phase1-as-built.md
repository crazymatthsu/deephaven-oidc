# Entra Web IDE Login (Phase 1) — As-Built Notes

**Date:** 2026-07-27/28
**Commit:** `784cf95` (on `main`; follows the stack-separation PR
[#3](https://github.com/crazymatthsu/deephaven-oidc/pull/3))
**Status:** Implemented and smoke-verified against a mock issuer; live tenant validation pending
([Phase 4 setup](ENTRA-IMPLEMENTATION-PLAN.md#phase-4--entra-tenant-setup-guide)).

## What was built

[`js-plugin-auth-entra/`](../../js-plugin-auth-entra) — a Deephaven web IDE login plugin that
signs users in directly against Microsoft Entra ID with **MSAL.js (auth-code + PKCE redirect)**:

- **Enterprise SSO**: a user already signed into their corporate account in the browser gets
  silent sign-on — no password prompt.
- **Microsoft Authenticator MFA**: injected natively by `login.microsoftonline.com` per the
  tenant's Conditional Access / Security Defaults policy; nothing MFA-specific lives in our code.
- After the redirect returns, the access token is handed to the server handler with
  `client.login({ type: 'io.deephaven.oidc.entra.EntraOidcAuthenticationHandler', token })`.
- Renewal: `acquireTokenSilent` (MSAL refresh-token cache in `localStorage`), falling back to a
  new `loginRedirect` on `InteractionRequiredAuthError`.

## Plugin contract (mirrors `@deephaven/js-plugin-auth-keycloak@0.2.0`)

Derived by unpacking the published Keycloak plugin — the only known-working reference for the
Community web UI plugin loader:

| Aspect | Value |
| --- | --- |
| Bundle | Single unminified **CommonJS** file `dist/index.js` (Vite lib build) |
| Export | `exports.AuthPlugin = { Component, isAvailable(authHandlers) }` |
| Login plumbing | Delegated to `AuthPluginBase` (from `@deephaven/auth-plugins`) via async `getLoginOptions()` → `{ type, token }` |
| Component props | `{ authConfigValues: Map<string,string>, children }` |
| Externals (provided by the web UI's module shim) | `react`, `@deephaven/auth-plugins`, `@deephaven/jsapi-components` — **only these three** |
| Bundled in | `@azure/msal-browser` (~554 KB total; plugins must be self-contained, no CDN) |
| Deliberately avoided | `@deephaven/log` — its shim interop is the bug that broke the Keycloak plugin on current web UIs (see [auth-keycloak-js-plugin-fix.md](../auth-keycloak-js-plugin-fix.md)); we use `console` instead |

## Packaging

- The [Entra Dockerfile](../../deephaven-entra-oidc-server/docker/deephaven/Dockerfile) builds the
  plugin in a `node:20-slim` stage (`npm install && npm run build`) — **no JS toolchain needed on
  the host** — and installs it as:
  - `/opt/deephaven/config/js-plugins/@deephaven-oidc/js-plugin-auth-entra/dist/index.js` (+ `package.json`)
  - `/opt/deephaven/config/js-plugins/manifest.json` (same layout the `web-plugin-packager` produces)

## Configuration

Browser-side values are exposed through Deephaven's generic
`-Dauthentication.client.configuration.list` mechanism (no server-code change):

| Property (seen by the plugin) | Compose env | Notes |
| --- | --- | --- |
| `authentication.oidc.entra.tenant-id` | `ENTRA_TENANT_ID` | required |
| `authentication.oidc.entra.spa-client-id` | `ENTRA_SPA_CLIENT_ID` | optional — empty ⇒ browser login reports missing config; gRPC-only use unaffected |
| `authentication.oidc.entra.scope` | `ENTRA_WEB_SCOPE` | e.g. `api://<app-id-uri>/access_as_user` |

Also added: `ENTRA_ISSUER_URI` compose override of the token issuer (defaults to the real
`https://login.microsoftonline.com/<tenant>/v2.0`) — exists to enable offline testing (below).

Entra portal prerequisite: an app registration **Single-page application** platform whose redirect
URI exactly equals the IDE URL (`http://localhost:10000/ide/` locally; HTTPS in production — Entra
allows plain `http` only for localhost).

## Smoke test without a tenant (mock issuer) — procedure + results

The server handler fetches the issuer's OIDC discovery document at startup, so the stack cannot
boot against a nonexistent tenant. Offline procedure:

1. Serve a minimal discovery doc + JWKS locally (static JSON; a throwaway RSA key). **Gotcha:**
   responses must carry `Content-Type: application/json` — Spring rejects
   `application/octet-stream` with `UnknownContentTypeException` (python `http.server` needs a
   `guess_type` override).
2. Start the stack with `ENTRA_ISSUER_URI=http://host.containers.internal:<port>/mock` plus dummy
   tenant/audience/SPA values.
3. Open `http://localhost:10000/ide/`.

Observed results (2026-07-28):

- Server booted cleanly against the mock issuer (discovery fetched, handler initialized).
- The IDE loaded the plugin from the manifest (no *"No login plugins found"*), `isAvailable`
  matched the Entra handler, and MSAL redirected the browser to the **real** Microsoft endpoint:
  `https://login.microsoftonline.com/mock-tenant/oauth2/v2.0/authorize` with exactly the
  configured `client_id`, `scope=api://mock/access_as_user openid profile offline_access`,
  `redirect_uri=http://localhost:10000/ide/`, `response_type=code`, `code_challenge_method=S256`.
- Microsoft answered `AADSTS900023` (invalid tenant identifier `mock-tenant`) — the expected and
  correct failure for a fake tenant. Everything on the Deephaven side of the flow works.

Still to validate live once a tenant exists: completing the sign-in + Authenticator approval, the
token handshake with the server, and >60-minute silent-renewal behavior.

## Related commit history (on `main`)

- `e317b3c` — merge of PR #3 (stack separation + MFA-capable Java client flows)
- `784cf95` — this Phase 1 implementation
- `10cc408`/`7df3ae4`/`70fab52`/`e17145f`/`f8b7d0f`/`c94ff24` — Entra setup architecture diagrams
  (drawio + SVG)
