# Deephaven OIDC — Keycloak or direct Microsoft Entra ID

OIDC single sign-on for open-source [Deephaven Community Core](https://deephaven.io/core/docs/) with
entitlement-based **row-level security** over a live, keyed orders table — runnable locally with
**podman compose**, deployable to **AWS EKS** (see [`deploy/eks/DESIGN.md`](deploy/eks/DESIGN.md)).

Two **switchable** identity implementations live side by side:

| | Keycloak stack (default) | Direct Entra ID stack |
| --- | --- | --- |
| Server module | [`deephaven-keycloak-oidc-server`](deephaven-keycloak-oidc-server) | [`deephaven-entra-oidc-server`](deephaven-entra-oidc-server) |
| Identity provider | Local Keycloak container (demo realm) | Your Microsoft Entra tenant (no local IdP) |
| Auth handler | Published `deephaven-oidc-authentication-provider` (pac4j) | Custom `EntraOidcAuthenticationHandler` fat jar (Spring `JwtDecoder`) |
| Start | `scripts/start.sh` (or `scripts/start.sh keycloak`) | `scripts/start.sh entra` (needs `ENTRA_TENANT_ID`, `ENTRA_AUDIENCE`) |
| Java clients | default, or `AUTH_PROVIDER=keycloak` | `AUTH_PROVIDER=entra` (+ `ENTRA_*` vars) |
| User MFA | Whatever the realm enforces (demo: none) | ✅ Microsoft Authenticator via device-code / interactive MSAL flows |
| Web IDE login | ✅ works (Keycloak JS plugin) | ✅ built ([js-plugin-auth-entra](js-plugin-auth-entra): MSAL.js auth-code+PKCE — enterprise SSO + Authenticator MFA; needs `ENTRA_SPA_CLIENT_ID`/`ENTRA_WEB_SCOPE`) |
| Row-level security | Script-level only (cooperative — any authenticated user can reach all rows) | ✅ **enforced server-side**: custom `AuthorizationProvider` gates every table fetch/write by Entra roles; consoles superuser-only ([details](deephaven-entra-oidc-server/README.md#server-side-entitlement-enforcement-custom-server-assembly)) |
| Status | Verified end-to-end locally | ✅ **Live-verified against a real Entra tenant** — web IDE SSO + Authenticator MFA (3 personas, 3 browsers), role-enforced reads/writes, interactive/device-code sign-in, client-credentials daemon ([results](docs/oidc/entra-live-validation-results.md)) |

Remaining Entra work is planned in detail in
[`docs/oidc/ENTRA-IMPLEMENTATION-PLAN.md`](docs/oidc/ENTRA-IMPLEMENTATION-PLAN.md).
The Keycloak path is based on the official guide:
[Keycloak / OIDC authentication](https://deephaven.io/core/docs/how-to-guides/authentication/auth-keycloak/).

## What's in the box

| Module | Contents |
| --- | --- |
| `deephaven-keycloak-oidc-server` | The Keycloak stack: custom Deephaven image (OIDC provider jar + Keycloak web-login plugin + orders app), Keycloak realm import, `compose.yaml` |
| `deephaven-entra-oidc-server` | The direct Entra stack: `EntraOidcAuthenticationHandler` + fat jar, Deephaven image (bakes in the web login plugin), `compose.yaml` (no IdP container) |
| `js-plugin-auth-entra` | Web IDE login plugin for Entra (MSAL.js, auth-code + PKCE → enterprise SSO + Authenticator MFA); built inside the Entra Docker image |
| `deephaven-keycloak-oidc-client` | `OrderSimulator` (publishes mock orders via Flight DoPut into the keyed input table) and `OrderSubscriber` (live Barrage subscription to the caller's entitled view) — both work against either stack via `AUTH_PROVIDER` |
| `deephaven-keycloak-oidc-common` | Shared config, Keycloak token client, **MSAL4J Entra token client** (client-credentials, device-code + Authenticator MFA, interactive, legacy ROPC), OIDC-authenticated `BarrageSession` factory |
| `deploy/eks` | Kubernetes manifests for the Keycloak stack (ALB/gRPC ingress, TLS) + security design doc for on-prem → EKS access |
| `deploy/eks-entra` | Kubernetes manifests for the direct-Entra stack — no in-cluster IdP, no server secrets, single ingress ([README](deploy/eks-entra/README.md)) |

## Demo identities

| Login | Password | Realm role | Sees |
| --- | --- | --- | --- |
| `alice` | `alice` | `trader-us` | US orders only (`orders_us`) |
| `bob` | `bob` | `trader-emea` | EMEA orders only (`orders_emea`) |
| `carol` | `carol` | `dh-admin` | all regions (`orders_all`) |
| `order-simulator` (service account) | secret `order-simulator-secret` | `writer` | publishes into `orders` |

Keycloak admin console: `http://keycloak.local:6060` — `admin` / `admin` (master realm).

## Row-level security model

The server runs an [application-mode](https://deephaven.io/core/docs/how-to-guides/application-mode/)
script ([`orders_app.py`](deephaven-keycloak-oidc-server/docker/deephaven/app.d/orders_app.py)) that
publishes:

- `orders` — input table **keyed on `OrderId`** (writes with an existing id update in place),
- `entitlements` — keyed input table mapping realm role → entitled `Region`,
- `orders_us` / `orders_emea` / `orders_all` — `orders.where_in(entitlements.where(Role==…), ["Region"])`.

Because the views filter *through the ticking entitlements table*, adding or deleting an entitlement row
in the IDE updates every affected subscriber immediately. Clients decode the realm roles from their own
access token and subscribe to the matching view.

> **Honest caveat (Keycloak stack):** open-source Deephaven has **no built-in entitlement system**
> (that's a Deephaven Enterprise feature). On the Keycloak stack this is *script-level* filtering: any
> authenticated user who can run console code or fetch the `orders` field directly can see all rows.
> The hardened EKS deployment therefore disables the console; gRPC-layer options are discussed in
> [`deploy/eks/DESIGN.md`](deploy/eks/DESIGN.md#authorization--row-level-security).
>
> **The direct Entra stack closes this gap**: its custom server assembly enforces the entitlements
> server-side at ticket resolution — non-admin users *cannot* fetch tables their roles aren't entitled
> to (regardless of client or route), input-table writes require the `writer` role, and consoles are
> superuser-only. See the
> [enforcement notes](deephaven-entra-oidc-server/README.md#server-side-entitlement-enforcement-custom-server-assembly).

## Switching between the two stacks

```bash
scripts/start.sh                # Keycloak stack (default; full quickstart below)
scripts/start.sh entra          # Direct Entra stack (needs ENTRA_TENANT_ID + ENTRA_AUDIENCE)
scripts/stop.sh [keycloak|entra]
scripts/logs.sh [keycloak|entra] [service]
```

Run one stack at a time (both bind Deephaven to port 10000). Clients pick their token source with
`AUTH_PROVIDER=keycloak|entra` — see
[`deephaven-keycloak-oidc-client/README-ENTRA.md`](deephaven-keycloak-oidc-client/README-ENTRA.md)
for the Entra variants, including the **device-code sign-in with Microsoft Authenticator MFA**
(`ENTRA_USER_FLOW=devicecode`, the default — no password ever touches the client program).

## Quickstart (podman)

Prereqs: podman with a running machine (**≥ 6 GiB memory**: `podman machine set --memory 6144`),
`podman-compose` (`pip3 install --user podman-compose`), JDK 17+, and one hosts entry so the browser,
the Deephaven container, and the Java clients all reach Keycloak at the same URL:

```bash
sudo sh -c 'echo "127.0.0.1 keycloak.local" >> /etc/hosts'
```

**1. Start the stack** (first run builds the image and pulls ~2 GB):

```bash
scripts/start.sh          # checks podman machine + hosts entry, builds, starts, waits until ready
```

The script prints the endpoints and demo credentials when both services are up. Other helpers:
`scripts/logs.sh [deephaven|keycloak]` follows logs, `scripts/stop.sh` tears the stack down (state is
ephemeral: the realm re-imports and the orders table re-seeds on the next start). Prefer raw compose?
`podman compose -f deephaven-keycloak-oidc-server/compose.yaml up --build -d` does the same without the
checks — the Deephaven container restarting until Keycloak has imported the realm is expected on first
boot.

**2. Log in from the browser** as `carol` / `carol` → in the IDE, open **Panels** and look at `orders`,
`entitlements`, `orders_us`, `orders_emea`, `orders_all`.

**3. Publish live orders** (service-account auth, keyed upserts):

```bash
./gradlew :deephaven-keycloak-oidc-client:runSimulator
```

**4. Subscribe as different users** (separate terminals) and watch row-level security in action:

```bash
./gradlew :deephaven-keycloak-oidc-client:runSubscriber -Puser=alice -Ppassword=alice   # US rows only
./gradlew :deephaven-keycloak-oidc-client:runSubscriber -Puser=bob   -Ppassword=bob     # EMEA rows only
./gradlew :deephaven-keycloak-oidc-client:runSubscriber -Puser=carol -Ppassword=carol   # everything
```

**5. Change entitlements live**: as `carol` in the IDE, add a row (`trader-us`, `EMEA`) to
`entitlements` — alice's subscription immediately starts receiving EMEA rows; delete the row and they
vanish again.

Tear down with `podman compose -f deephaven-keycloak-oidc-server/compose.yaml down`.

### Client configuration

The clients read environment variables (defaults target the local stack):

| Variable | Default | EKS example |
| --- | --- | --- |
| `DH_HOST` / `DH_PORT` / `DH_TLS` | `localhost` / `10000` / `false` | `deephaven.example.com` / `443` / `true` |
| `KC_URL` | `http://keycloak.local:6060` | `https://auth.deephaven.example.com` |
| `KC_REALM` / `KC_CLIENT_ID` | `deephaven_core` / `deephaven` | same |
| `KC_SIM_CLIENT_ID` / `KC_SIM_CLIENT_SECRET` | `order-simulator` / `order-simulator-secret` | from your secret store |

## How authentication works

1. The Deephaven image bundles the published
   [`deephaven-oidc-authentication-provider`](https://central.sonatype.com/artifact/io.deephaven/deephaven-oidc-authentication-provider)
   fat jar (pac4j-based) and enables it via
   `AuthHandlers=io.deephaven.authentication.oidc.OidcAuthenticationHandler`.
2. Browser: the bundled [`@deephaven/js-plugin-auth-keycloak`](https://www.npmjs.com/package/@deephaven/js-plugin-auth-keycloak)
   plugin redirects the web IDE to Keycloak and completes the OIDC code flow.
3. Java clients: obtain an access token themselves (password or client-credentials grant) and open the
   gRPC session with `authenticationTypeAndValue("io.deephaven.authentication.oidc.OidcAuthenticationHandler <token>")`.
4. The server validates every presented token against Keycloak (issuer/JWKS) before admitting a session.

All parties must use the **same Keycloak URL** (`http://keycloak.local:6060` locally) so token issuer and
validation agree — that's the reason for the hosts entry; inside the Deephaven container the name maps to
the podman host gateway via `extra_hosts`.

## Production / EKS

See [`deploy/eks/`](deploy/eks/): TLS at an ALB (gRPC-aware target group) with ACM certs, OIDC end to
end, console disabled, private subnets, plus a hardening ladder (IP allowlist → mTLS → VPN/PrivateLink)
and guidance on federating Keycloak with corporate Windows SSO (AD/Kerberos or Entra ID brokering).

## Security caveats (read before reusing)

- Every credential in this repo (`alice`/`bob`/`carol`, admin, the simulator secret) is public demo data
  — regenerate the realm for anything real.
- Keycloak runs `start-dev` with an in-container file DB and `sslRequired=none` locally — dev only.
- Script-level row filtering is a demo of *entitlement-driven views*, not a substitute for server-side
  authorization; see the caveat above and the EKS design doc.
- Local traffic is plaintext HTTP; TLS is introduced at the EKS ingress.

## Compatibility patches in the Docker image

The published `@deephaven/js-plugin-auth-keycloak@0.2.0` (last released 2023) needs two small patches
to work with current Deephaven web UIs and Keycloak ≥ 25; both are applied by `sed` in the
[Dockerfile](deephaven-keycloak-oidc-server/docker/deephaven/Dockerfile) with explanatory comments:

1. **`@deephaven/log` interop** — the plugin was compiled expecting the UI's module shim to expose the
   package as a namespace (`{ Log }`), but current UIs expose the default export, so the plugin threw at
   load time and the IDE reported *"No login plugins found"*.
2. **OIDC nonce validation** — the plugin bundles an old `keycloak-js` that validates the nonce claim on
   the access/refresh tokens too; Keycloak 25+ only puts `nonce` in the ID token, so every login failed
   with *"Unable to login"* (invalid-nonce). The patch validates the ID token nonce only, matching
   keycloak-js 24+.

Full write-up with symptoms, root-cause analysis, and how it was diagnosed:
[docs/auth-keycloak-js-plugin-fix.md](docs/auth-keycloak-js-plugin-fix.md).

## Version pinning

Deephaven `41.3` (server image, client libraries, and OIDC provider jar must match) and Keycloak
`26.2`. Bump `deephaven` in [`gradle/libs.versions.toml`](gradle/libs.versions.toml) and
`DEEPHAVEN_VERSION` in the Dockerfiles together. Dagger is pinned to `2.56.2` to match the
runtime shipped in `ghcr.io/deephaven/server:41.3`.
