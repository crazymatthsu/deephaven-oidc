# Entra ID for Deephaven Community — Implementation Plan (remaining work)

**Date:** 2026-07-27
**Status:** Roadmap for the phases NOT yet implemented.

## Where we are

Done (this repo):

- ✅ **Server handler** — `deephaven-entra-oidc-server`: `EntraOidcAuthenticationHandler`
  (Spring `NimbusJwtDecoder`, issuer + optional audience validation), packaged as a self-contained
  fat jar, deployed by its own compose stack (`scripts/start.sh entra`).
- ✅ **Java client token acquisition** — `EntraTokenClient` (MSAL4J): client-credentials
  (simulator), **device-code** and **interactive browser** user flows (both complete Microsoft
  Authenticator MFA), ROPC kept only as a legacy fallback; shared MSAL cache + `acquireSilently()`.
- ✅ **Switchable stacks** — Keycloak (`deephaven-keycloak-oidc-server`) and direct Entra
  (`deephaven-entra-oidc-server`) side by side; clients switch with `AUTH_PROVIDER`.
- ✅ **Phase 1: Web IDE login via MSAL.js** — implemented 2026-07-27 as
  [`js-plugin-auth-entra`](../../js-plugin-auth-entra) (details below kept as as-built reference).
  Smoke-verified against a mock issuer: plugin loads in the IDE, `isAvailable` matches the Entra
  handler, and MSAL redirects to `login.microsoftonline.com` with the correct client_id/scope/PKCE
  parameters. **Live tenant validation still pending** (needs Phase 4).

Remaining, in recommended order:

| Phase | What | Effort | Unblocks |
|---|---|---|---|
| 1 | Web IDE login via MSAL.js | ✅ done (live validation pending) | Browser users on the Entra stack |
| 2 | Server-side identity & roles | Medium | Real per-user auditing; groundwork for authz |
| 3 | Token lifecycle in long-running clients | Low | Daemons running past token expiry |
| 4 | Entra tenant setup guide + live E2E test | Low (docs) + portal work | All live testing |
| 5 | Tests & CI | Medium | Regression safety |
| 6 | EKS deployment variant | Medium | Production-ish deploy without Keycloak |
| 7 | End-state decision: retire Keycloak vs broker | Decision | Simplified architecture |

Phase 4 is a prerequisite for *live-testing* every other phase — do it as soon as a tenant is
available; it needs no code.

---

## Phase 1 — Web IDE login via MSAL.js  ✅ IMPLEMENTED (as-built notes)

> Implemented 2026-07-27: [`js-plugin-auth-entra`](../../js-plugin-auth-entra), built in a node
> stage of the Entra Dockerfile and installed under `/js-plugins` with a manifest. Browser config
> is exposed via `authentication.oidc.entra.tenant-id` / `spa-client-id` / `scope` on
> `authentication.client.configuration.list` (compose env `ENTRA_SPA_CLIENT_ID`,
> `ENTRA_WEB_SCOPE`). A `@deephaven/log` dependency was deliberately avoided (known shim-interop
> bug). Offline smoke test: `ENTRA_ISSUER_URI` override + a local mock discovery/JWKS server lets
> the stack boot without a tenant; the IDE then demonstrably redirects to
> `login.microsoftonline.com/<tenant>/oauth2/v2.0/authorize` with correct client_id, scope, and
> S256 PKCE. Still to do live (needs Phase 4): complete a real sign-in + Authenticator MFA, token
> handshake with the server, and >60-min renewal behavior.

**Goal:** browser users open `http://localhost:10000/ide`, get redirected to the Entra sign-in
page (Authenticator MFA happens natively there), and land in the IDE — the same UX the Keycloak
stack has today.

**Why it's the hard part:** Deephaven Community has no generic OIDC browser plugin; the only
published one (`@deephaven/js-plugin-auth-keycloak`) is Keycloak-specific. We must build and
package our own JS login plugin.

### 1.1 How Deephaven JS auth plugins work (reference: auth-keycloak plugin)

- The web UI loads plugins from `/js-plugins/` (server dir `/opt/deephaven/config/js-plugins/`,
  populated in the Dockerfile). Each plugin has a `plugin.json`/manifest and a JS module.
- An auth plugin exports `AuthPlugin = { AuthPluginComponent, isAvailable(authHandlers, authConfigValues) }`:
  - `isAvailable` receives the server's advertised auth handlers (from `AuthHandlers`) and the
    key/value pairs the server exposes via `-Dauthentication.client.configuration.list=...`.
    Return true when `io.deephaven.oidc.entra.EntraOidcAuthenticationHandler` is present.
  - `AuthPluginComponent` is a React component that performs the login and then calls
    `client.login({ type: '<handler class name>', token: '<access_token>' })` on the JS API client.
- Study `@deephaven/js-plugin-auth-keycloak` source (deephaven-plugins repo /
  `npm pack @deephaven/js-plugin-auth-keycloak` and read `dist/index.js`) — our plugin is the same
  shape with MSAL.js in place of keycloak-js.

### 1.2 Server-side config exposure

The browser needs tenant + SPA client id. Reuse the existing property mechanism:

```text
-Dauthentication.oidc.entra.tenant-id=<tenant-id>
-Dauthentication.oidc.entra.spa-client-id=<spa-app-client-id>
-Dauthentication.oidc.entra.scope=api://<app-id-uri>/access_as_user
-Dauthentication.client.configuration.list=AuthHandlers,authentication.oidc.entra.tenant-id,authentication.oidc.entra.spa-client-id,authentication.oidc.entra.scope
```

Add these to `deephaven-entra-oidc-server/compose.yaml` (new env vars `ENTRA_SPA_CLIENT_ID`,
`ENTRA_SCOPE`). No server-code change needed — the config list is generic.

### 1.3 Plugin implementation (`js-plugin-auth-entra/`, new top-level dir)

Stack: TypeScript + Vite (library build, single ESM file), `@azure/msal-browser` ^3.

```text
js-plugin-auth-entra/
  package.json          # name: @<org>/js-plugin-auth-entra
  vite.config.ts
  src/index.ts          # exports AuthPlugin
  src/EntraLogin.tsx    # login component
```

Component logic (auth-code + PKCE redirect flow — MFA comes free):

```ts
const msal = new PublicClientApplication({
  auth: {
    clientId: spaClientId,                                        // from authConfigValues
    authority: `https://login.microsoftonline.com/${tenantId}`,
    redirectUri: window.location.origin + window.location.pathname,
  },
  cache: { cacheLocation: 'sessionStorage' },
});
await msal.initialize();
const redirectResult = await msal.handleRedirectPromise();        // completing a round-trip?
const account = redirectResult?.account ?? msal.getAllAccounts()[0];
if (!account) {
  await msal.loginRedirect({ scopes: [scope] });                  // → Entra sign-in + Authenticator
  return;                                                          // page navigates away
}
const { accessToken } = await msal.acquireTokenSilent({ scopes: [scope], account });
await client.login({ type: 'io.deephaven.oidc.entra.EntraOidcAuthenticationHandler',
                     token: accessToken });
```

Renewal: re-run `acquireTokenSilent` before expiry (MSAL caches the refresh token); on
`InteractionRequiredAuthError` fall back to `loginRedirect`. Deephaven's JS API also supports
re-login on connection loss — hook the same acquire path.

### 1.4 Packaging & deployment

- Build → `dist/index.js`; pack with `ghcr.io/deephaven/web-plugin-packager` (same stage pattern
  as the Keycloak Dockerfile) **or** simply `COPY` the built plugin dir + manifest into
  `/opt/deephaven/config/js-plugins/@<org>/js-plugin-auth-entra/`.
- Add the stage/COPY to `deephaven-entra-oidc-server/docker/deephaven/Dockerfile`.

### 1.5 Entra app registration (SPA)

Separate **SPA** platform registration (or SPA platform on the public client app): redirect URI
`http://localhost:10000/ide/` (and the production URL later), delegated permission to the API scope
`access_as_user`. SPA platform = tokens issued via PKCE with CORS; do NOT use the "Web" platform.

### 1.6 Acceptance criteria

- Opening `/ide` with no session redirects to `login.microsoftonline.com`; MFA prompt appears in
  Microsoft Authenticator; after approval the IDE loads with panels visible.
- Token renewal: leave the IDE open > access-token lifetime (default 60–90 min) — no forced logout.
- A user with no assigned app role can log in but sees only what Phase 2 policy allows.

**Estimated effort:** 3–5 days including plugin debugging (use the playwright-container technique
from [auth-keycloak-js-plugin-fix.md](../auth-keycloak-js-plugin-fix.md) — it was decisive last time).

---

## Phase 2 — Server-side identity & roles

**Goal:** stop admitting every valid token as `AuthContext.SuperUser`; carry the real user identity
and roles into Deephaven.

Changes in `EntraOidcAuthenticationHandler.validate()`:

1. Extract identity: `oid` (stable object id) and `preferred_username` / `upn`.
2. Extract roles: `roles` (app roles — recommended), else `groups` (beware the 200-group overage
   limit → use app roles), else none.
3. Return a non-superuser context, e.g. `new AuthContext.Anonymous()` is wrong — Community's
   `AuthContext` options are limited (`SuperUser`, `Anonymous`); model users as a custom
   `AuthContext` subclass carrying `userId` + `Set<String> roles` so server-side hooks
   (`AuthorizationProvider`, ticket resolvers) can consult it.
4. Config switch `authentication.oidc.entra.superuser-roles=dh-admin` — only tokens holding one of
   these become `SuperUser`; everyone else gets the user context.

Honest scoping note (unchanged from the Keycloak stack): Community Core has no built-in
entitlement engine; row filtering stays script-level (`orders_app.py` views). Real enforcement
requires a custom `io.deephaven.server.auth.AuthorizationProvider` wiring — document this as the
follow-on if/when needed, with `deephaven.console.disable=true` as the interim hardening.

**Acceptance:** server log shows `login user=<oid/upn> roles=[...]`; a token without the configured
audience or from another tenant is rejected (already true); a `dh-admin` token gets superuser, a
`trader-us` token does not.

---

## Phase 3 — Token lifecycle in long-running clients

**Goal:** `OrderSimulator` (daemon) and `OrderSubscriber` (interactive) survive past the ~60–90 min
access-token lifetime.

- Deephaven's session stays alive on its own rotating session token after the handshake, so the
  *existing* connection generally survives; the Entra token matters when (re)connecting.
- Simulator: before each reconnect (and proactively via `Token.expiresWithin(Duration.ofMinutes(5))`
  — already implemented on the record), call `clientCredentialsGrant` again (MSAL caches/refreshes
  internally). Wrap session creation in a small `TokenSupplier` so reconnect logic has a fresh token.
- Subscriber: call `EntraTokenClient.acquireSilently()` (already implemented, uses the MSAL refresh
  token) and only fall back to a new device-code prompt when silent renewal fails.
- Add a reconnect-with-fresh-token path around `DeephavenSessions.newSession` (retry on
  `UNAUTHENTICATED`).

**Acceptance:** simulator runs > 2 hours against the Entra stack without manual restart; subscriber
survives a forced disconnect after token expiry with at most one silent renewal (no new MFA prompt
while the refresh token is valid).

---

## Phase 4 — Entra tenant setup guide (prerequisite for live testing)

Portal work; produces the values consumed by `.env` / client env. Summary of registrations:

| Registration | Type | Purpose | Key settings |
|---|---|---|---|
| `deephaven-api` | API | The audience the server validates | Expose an API: Application ID URI `api://<client-id>`; scope `access_as_user`; **app roles** `trader-us`, `trader-emea`, `dh-admin`, `writer` (allow users+applications for `writer`) |
| `deephaven-users` | Public client | Device-code / interactive user sign-in | **Allow public client flows: Yes**; redirect URI `http://localhost` (Mobile & desktop) for the interactive flow; API permission: delegated `access_as_user` (admin-consented) |
| `deephaven-simulator` | Confidential client | Daemon (client credentials) | Client secret; API permission: **application** role `writer` on `deephaven-api` (admin-consented) |
| SPA platform (Phase 1) | SPA | Browser IDE | Redirect URI `http://localhost:10000/ide/`; delegated `access_as_user` |

Steps:

1. Create the three registrations; note tenant id and client ids.
2. On `deephaven-api`: Expose an API → set App ID URI, add scope `access_as_user`;
   App roles → add the four roles above.
3. Assign test users to app roles: Entra ID → Enterprise applications → `deephaven-api` →
   Users and groups → add user with role (e.g. alice→trader-us, bob→trader-emea, carol→dh-admin).
4. **Important — `aud` note:** v2.0 client-credentials tokens for `api://<id>/.default` carry
   `aud = api://<client-id>` *or* the bare client id depending on configuration. The handler accepts
   the configured audience against both `aud` and `azp`; set `ENTRA_AUDIENCE` to whichever your
   tokens actually carry (decode one at jwt.ms first). Optionally set the API's
   `requestedAccessTokenVersion: 2` in the manifest to force v2 tokens.
5. MFA: enable Security Defaults (free tier) or a Conditional Access policy requiring MFA;
   have each test user register Microsoft Authenticator at https://aka.ms/mfasetup.
6. Fill `deephaven-entra-oidc-server/.env` (tenant id + audience) and export the client-side
   `ENTRA_*` variables per [README-ENTRA](../../deephaven-keycloak-oidc-client/README-ENTRA.md).

Live E2E test script (once configured):

```bash
scripts/start.sh entra
# terminal 1 — daemon, no MFA:
AUTH_PROVIDER=entra ENTRA_...=... ./gradlew :deephaven-keycloak-oidc-client:runSimulator
# terminal 2 — device code; approve in Authenticator:
AUTH_PROVIDER=entra ENTRA_...=... ./gradlew :deephaven-keycloak-oidc-client:runSubscriber
# negative tests: wrong audience → server rejects; ENTRA_USER_FLOW=ropc on an MFA account → AADSTS50076
```

---

## Phase 5 — Tests & CI

- **Handler unit tests** (new `deephaven-entra-oidc-server/src/test/java`):
  - Generate an RSA key with Nimbus (`new RSAKeyGenerator(2048).keyID("t").generate()`), serve
    `/.well-known/openid-configuration` + `/jwks` from an embedded HTTP server
    (`com.sun.net.httpserver.HttpServer` — no new deps), point `issuer-uri` at it.
  - Cases: valid token → accepted; expired / wrong issuer / wrong audience / garbage → rejected;
    `azp` fallback accepted; `Bearer ` prefix stripped; missing config → clear startup error.
  - Needs `testImplementation(libs.deephaven.authentication)` (SPI types) — mind that the handler
    reads config from system properties: set/clear them per test.
- **Client-side**: `AppConfig` parsing tests (provider/flow parsing, env vs sysprop precedence);
  `EntraTokenClient.rolesFromToken` claim-extraction tests with hand-built JWT payloads.
- **CI**: GitHub Actions `gradle build :deephaven-entra-oidc-server:fatJar` on push/PR; optional
  job building both Docker images. Live Entra tests stay manual (need a tenant + a human for MFA).

---

## Phase 6 — EKS deployment variant (direct Entra)

Mirror `deploy/eks/` for the Entra stack:

- Drop `10-keycloak.yaml`; Deephaven Deployment uses the Entra image; `ENTRA_TENANT_ID`/
  `ENTRA_AUDIENCE` via ConfigMap (they are not secrets); egress to `login.microsoftonline.com:443`.
- Keep the gRPC-aware ALB/TLS setup unchanged (it is auth-agnostic).
- Redirect URIs for Phase 1 SPA move to the public hostname.

---

## Phase 7 — End state: retire Keycloak, or keep it as broker?

Decide after Phase 1 lands:

- **Direct Entra everywhere** (this plan's trajectory): delete the Keycloak modules → fewer moving
  parts, one IdP, but you own the JS plugin and any future OIDC edge cases.
- **Keycloak as Entra broker** (documented alternative, [entra-id-vs-keycloak.md](../entra-id-vs-keycloak.md)
  Option A): keep the working Keycloak web login and federate Entra into the realm
  (Identity Providers → Microsoft/OIDC; map Entra app roles/groups → realm roles). Zero custom JS,
  at the cost of running Keycloak forever. If chosen, Phase 1 becomes unnecessary — implement the
  realm federation in `deephaven_realm.json` instead.

Record the decision in this file when made.
