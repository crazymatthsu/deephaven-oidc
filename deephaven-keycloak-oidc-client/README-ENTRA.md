# Entra ID client auth (MSAL)

When `AUTH_PROVIDER=entra`, the demo clients acquire tokens with **MSAL4J** directly from Microsoft
Entra ID instead of Keycloak. The server must be running the Entra stack (`scripts/start.sh entra`,
see [`deephaven-entra-oidc-server/README.md`](../deephaven-entra-oidc-server/README.md)).

## Environment

| Variable | Required | Purpose |
|----------|----------|---------|
| `AUTH_PROVIDER` | yes (`entra`) | Select Entra path |
| `ENTRA_TENANT_ID` | yes | Directory (tenant) ID |
| `ENTRA_CLIENT_ID` | yes | App registration client ID (public app for users, confidential app for the simulator) |
| `ENTRA_SCOPE` | yes | e.g. `api://<app-id>/.default` (simulator) or `api://<app-id>/access_as_user` (users) |
| `ENTRA_USER_FLOW` | no (default `devicecode`) | `devicecode` \| `interactive` \| `ropc` — see below |
| `ENTRA_CLIENT_SECRET` | simulator only | Confidential client secret |
| `DH_HOST` / `DH_PORT` / `DH_TLS` | as needed | Deephaven endpoint |
| `DH_USER` / `DH_PASSWORD` | `ropc` flow only | Legacy username/password (no MFA) |
| `ENTRA_ACCESS_TOKEN` | testing only | Pre-acquired token; skips MSAL entirely (used by the mock-issuer E2E tests) |

## User flows and Microsoft Authenticator MFA

| `ENTRA_USER_FLOW` | How it works | MFA (Authenticator) |
|---|---|---|
| `devicecode` (default) | Prints a URL + one-time code; sign in from **any** browser/device, approve the Authenticator push / number match | ✅ |
| `interactive` | Opens the system browser (auth-code + PKCE, localhost redirect) | ✅ |
| `ropc` | Sends username+password straight to the token endpoint | ❌ fails on MFA-enforced accounts (AADSTS50076) |

App-registration prerequisites: `devicecode` needs **"Allow public client flows"** enabled;
`interactive` needs `http://localhost` as a *Mobile and desktop applications* redirect URI.
Full tenant setup: [ENTRA-IMPLEMENTATION-PLAN.md Phase 4](../docs/oidc/ENTRA-IMPLEMENTATION-PLAN.md#phase-4--entra-tenant-setup-guide).

## Where to keep the values

One gitignored env file per client identity (templates committed alongside):
[`entra-pubsub.env.example`](entra-pubsub.env.example) (daemon: client ID + secret of the
confidential app) and [`entra-subscriber.env.example`](entra-subscriber.env.example) (humans:
public app client ID, no secret). Copy each to the same name without `.example`, fill in, and
load with `set -a; source <file>; set +a` before the gradle run. `ENTRA_CLIENT_ID` deliberately
differs between the two — don't share one shell export across both clients. The server's
`deephaven-entra-oidc-server/.env` never needs client secrets. In production, inject the secret
from a secret store instead of a file.

## Run simulator (client credentials — no MFA, service principal)

```bash
export AUTH_PROVIDER=entra
export ENTRA_TENANT_ID=...
export ENTRA_CLIENT_ID=...          # confidential app
export ENTRA_CLIENT_SECRET=...
export ENTRA_SCOPE="api://your-app-id/.default"

./gradlew :deephaven-keycloak-oidc-client:runSimulator
```

## Run subscriber (device code + Authenticator MFA — default)

No password is given to the program; identity comes from whoever completes the browser sign-in.

```bash
export AUTH_PROVIDER=entra
export ENTRA_TENANT_ID=...
export ENTRA_CLIENT_ID=...          # public app with "Allow public client flows"
export ENTRA_SCOPE="api://your-app-id/access_as_user"

./gradlew :deephaven-keycloak-oidc-client:runSubscriber
```

The console prints something like:

```text
=== Microsoft Entra ID sign-in required ===
To sign in, use a web browser to open https://microsoft.com/devicelogin and enter the code ABC-DEF-GHI ...
Approve the Microsoft Authenticator prompt (MFA) to finish.
```

Alternative flows:

```bash
ENTRA_USER_FLOW=interactive ./gradlew :deephaven-keycloak-oidc-client:runSubscriber   # system browser
ENTRA_USER_FLOW=ropc ./gradlew :deephaven-keycloak-oidc-client:runSubscriber \
    -Puser=alice@yourtenant.onmicrosoft.com -Ppassword='...'                          # legacy, no MFA
```

Assign Entra **app roles** (or groups) named `trader-us`, `trader-emea`, or `dh-admin` so the
subscriber can pick the entitled view.

## Token lifecycle (long-running clients)

Both demo clients now outlive the ~60–90 min access-token lifetime: tokens are wrapped in a
`RefreshingToken` that renews proactively near expiry — silently via the MSAL cache for users
(no new sign-in or Authenticator prompt while the refresh token is valid), via a fresh
client-credentials grant for the daemon — and both clients reconnect automatically with a
current token (5s→60s backoff) if the server restarts or the stream drops. The
`ENTRA_ACCESS_TOKEN` testing hook cannot be refreshed; expect reconnects to fail once it
expires. The MSAL cache is per-process, so each new subscriber process performs one interactive
sign-in; renewals within the process are silent.

## Keycloak remains the default

Omit `AUTH_PROVIDER` or set `AUTH_PROVIDER=keycloak` to keep the original local compose behaviour
(`-Puser=alice -Ppassword=alice`).
