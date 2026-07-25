# Fixing the Deephaven `@deephaven/js-plugin-auth-keycloak` browser login

**Date:** 2026-07-24
**Affects:** Deephaven Community Core 0.39.4 (web UI 0.108.0), `@deephaven/js-plugin-auth-keycloak@0.2.0`, Keycloak 26.2
**Status:** Fixed — both patches are applied automatically in
[`deephaven-keycloak-oidc-server/docker/deephaven/Dockerfile`](../deephaven-keycloak-oidc-server/docker/deephaven/Dockerfile)

## Summary

Following the official [Keycloak/OIDC guide](https://deephaven.io/core/docs/how-to-guides/authentication/auth-keycloak/)
with current images produces a broken browser login. The server side (OIDC handler jar, realm config,
`authentication.client.configuration.list`) is fine — Java clients authenticate without issue. The failures are
entirely inside the published JS login plugin, which was last released in 2023 (v0.2.0) and has drifted out of
compatibility in two independent ways. Both are fixed with one-line `sed` patches at Docker image build time.

## Symptom 1: "No login plugins found"

Opening `http://localhost:10000/ide` shows:

```
No login plugins found, please register a login plugin for auth handlers:
io.deephaven.authentication.oidc.OidcAuthenticationHandler
```

### What's actually happening

The browser console (not visible in the banner) shows the real error:

```
[@deephaven/app-utils.PluginUtils] Unable to load plugin '@deephaven/js-plugin-auth-keycloak'
TypeError: Cannot read properties of undefined (reading 'module')
```

The plugin bundle was compiled against an older web UI whose remote-module shim exposed `@deephaven/log`
as a namespace object, so the bundle contains:

```js
const log$1 = require("@deephaven/log");
const log = log$1.Log.module("...");   // expects { Log } namespace
```

Current web UIs (`web-client-ui` ≥ ~0.100, checked at 0.108.0) map the shim entry with a **default** import
(`remote-component.config.ts`: `import DeephavenLog from '@deephaven/log'`), so `require("@deephaven/log")`
returns the `Log` class itself. `log$1.Log` is therefore `undefined`, the module throws while loading, the UI
skips it, and no plugin claims the OIDC auth handler.

### Fix

Patch the single offending access to accept either shape:

```dockerfile
RUN sed -i 's/log\$1\.Log\.module/(log$1.Log || log$1).module/' \
    "/opt/deephaven/config/js-plugins/@deephaven/js-plugin-auth-keycloak/dist/index.js"
```

## Symptom 2: "Unable to login. Verify credentials."

After fix 1, the IDE correctly redirects to Keycloak and the credentials are accepted (the
`login-actions/authenticate` POST returns 302 back to the IDE), yet the page shows
*"Unable to login. Verify credentials."* and the console logs:

```
[AuthPluginBase] Unable to login: undefined
```

### What's actually happening

Network capture shows the authorization-code exchange at
`/realms/deephaven_core/protocol/openid-connect/token` returns **200 with valid tokens** — and the login
still fails, with the `keycloak.init()` promise rejecting with `undefined` *before* the exchange completes.

The plugin bundles an old `keycloak-js` (~v21) whose `authSuccess` validates the OIDC `nonce` claim on **all
three tokens**:

```js
if (useNonce && (kc.tokenParsed.nonce != oauth.storedNonce
              || kc.refreshTokenParsed.nonce != oauth.storedNonce
              || kc.idTokenParsed.nonce != oauth.storedNonce)) {
  kc.clearToken();
  promise && promise.setError();   // rejects with undefined
}
```

**Keycloak 25 removed the `nonce` claim from access and refresh tokens** — it is now only present in the ID
token (see the Keycloak 25 release notes). So `kc.tokenParsed.nonce` is `undefined`, the comparison always
fails, the freshly obtained tokens are discarded as an "invalid nonce", and the init promise rejects with no
error value. `keycloak-js` 24+ validates the nonce on the ID token only.

### Fix

Patch the check to the modern (ID-token-only) behavior:

```dockerfile
RUN sed -i 's/kc\.tokenParsed && kc\.tokenParsed\.nonce != oauth\.storedNonce || kc\.refreshTokenParsed && kc\.refreshTokenParsed\.nonce != oauth\.storedNonce || kc\.idTokenParsed && kc\.idTokenParsed\.nonce/kc.idTokenParsed \&\& kc.idTokenParsed.nonce/' \
    "/opt/deephaven/config/js-plugins/@deephaven/js-plugin-auth-keycloak/dist/index.js"
```

An alternative server-side workaround is Keycloak's `oidc-nonce-backwards-compatible-mapper` protocol mapper
(adds `nonce` back to the access token), but it does not cover the refresh token, which the old check also
inspects — so the client-side patch is the reliable fix.

## How it was diagnosed

No error detail is visible in the UI, so the flow was reproduced in headless Chromium (Playwright inside a
`mcr.microsoft.com/playwright` container, `--network=host --add-host keycloak.local:127.0.0.1`), capturing:

- console output — surfaced the plugin load `TypeError` behind symptom 1;
- network responses — proved the token exchange returned 200 despite the login failure;
- a `page.route()` rewrite of the plugin bundle that wrapped `keycloak.init()` with logging — pinned the
  rejection to the nonce check (rejects with `undefined`, before the exchange even completes).

## Verification

Headless end-to-end run after both patches:

1. `http://localhost:10000/ide` redirects to Keycloak (`response_type=code`, PKCE S256);
2. login as `alice`/`alice` succeeds, redirect returns with the auth code;
3. token exchange 200, nonce accepted, console logs
   `Authenticated with Keycloak API. Logging into Deephaven...` then `[AuthPluginBase] Logging in...`;
4. the IDE workspace renders (Panels/Console/Command History visible).

## Upstream notes

- The plugin lives in the archived `deephaven/deephaven-js-plugins` repo; v0.2.0 is the latest published
  version and receives no updates, so these patches are needed until Deephaven republishes it (or the
  packages move into an actively maintained repo).
- If you bump the Deephaven server version, the patches are harmless no-ops should the plugin ever ship
  fixed: the first pattern simply won't match, and the build continues (`sed` does not fail on no-match).
- If you bump Keycloak below 25 (not recommended), symptom 2 does not occur; the patch remains compatible.
