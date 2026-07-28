# Server-Side Entitlement Enforcement (Entra stack) — As-Built Notes

**Date:** 2026-07-28
**Commit:** `fa745c0` (on `main`; follows Phase 2 identity/roles, merged as
[PR #4](https://github.com/crazymatthsu/deephaven-oidc/pull/4))
**Status:** Implemented and E2E-verified against a mock issuer; live Entra tenant validation
pending ([Phase 4 setup](ENTRA-IMPLEMENTATION-PLAN.md#phase-4--entra-tenant-setup-guide)).

## What this delivers

Before this change, **any** authenticated user — web IDE or gRPC — could see **all** data: open-source
Deephaven's default authorization is allow-all, so the row-level security was script-level and
cooperative (clients politely picked their entitled view). Now, on the direct-Entra stack:

- A user logging into the web IDE (or connecting via any client/protocol) can fetch **only the
  tables their Entra roles are entitled to**. E.g. alice (`trader-us`) can open `orders_us`; the
  raw `orders`, `orders_emea`, `orders_all`, and `entitlements` tables are refused by the server.
- Denials surface as `NOT_FOUND`, so unauthorized users can't even confirm a table exists;
  Flight listings are filtered the same way.
- Input-table **writes** require an entitled role on the target (the demo grants the `writer`
  app role on the raw `orders` table — the simulator's service principal).
- The **console** (run-arbitrary-code) is superuser-only, closing the obvious bypass.
- Superusers (roles in `ENTRA_SUPERUSER_ROLES`, default `dh-admin`) bypass all checks.
- Every denial is audit-logged:
  `Denied table access: user=alice@mock.local roles=[trader-us] entitledRoles=writer`.

The Keycloak stack is untouched (stock community server; the entitlement attributes are inert there).

## Architecture

### Custom server assembly (the "option 1" from the design discussion)

Deephaven Community wires its `AuthorizationProvider` into the server's dagger graph, so it cannot
be replaced by a jar drop alone — a custom server main is required. We follow the **official
customization pattern** from deephaven-core's `server/jetty-app-custom` example (v0.39.4):

| Class (package `io.deephaven.oidc.entra.authz`) | Role |
| --- | --- |
| `EntraServerMain` | Entry point: `MainHelper.init` + factory, exactly like `JettyMain` |
| `EntraServerComponentFactory` | Dagger `@Component` mirroring `CommunityComponentFactory`'s module list (`JettyServerModule`, `FlightSqlModule`, channel-factory module, `CommunityDefaultsModule`) with `CommunityAuthorizationModule` **replaced** by `@Binds AuthorizationProvider → EntraAuthorizationProvider` |
| `EntraAuthorizationProvider` | Extends `AllowAllAuthorizationProvider`; overrides ticket authorization + input-table + console wirings |
| `EntraTicketAuthorization` | The fetch gate (details below) |
| `EntraInputTableAuthWiring` | Write gate |
| `EntraConsoleAuthWiring` | Console gate |
| `EntraEntitlements` | Pure policy helpers (attribute parsing, role intersection) |

Build notes:
- `com.google.dagger:dagger-compiler` **2.56.1** as `annotationProcessor` — version pinned to the
  dagger runtime shipped in `ghcr.io/deephaven/server:0.39.4`.
- Server artifacts are `compileOnly` (present in the image at runtime; kept out of the fat jar).
- `JettyClientChannelFactoryModule` lives in `deephaven-server-jetty-app`, which is **not
  published to Maven Central** — replicated inline in `EntraServerComponentFactory`
  (`ClientChannelFactoryModule` + `SslConfigModule` + a `@UserAgent` binding).
- The Docker image swaps the launch class with a one-line `sed` on the stock start script
  (`io.deephaven.server.jetty.JettyMain` → `io.deephaven.oidc.entra.authz.EntraServerMain`),
  preserving all of the script's env handling (`START_OPTS`, `EXTRA_CLASSPATH`, …).

### The fetch gate: ticket resolution + table attributes

Two facts (verified in deephaven-core v0.39.4 source) make one small hook sufficient:

1. **Every table-fetch route funnels through `TicketResolver.Authorization.transform`** —
   Flight `DoGet`/`getFlightInfo`, Barrage `DoExchange` subscriptions, `SessionService`
   exports, and web IDE object fetches all resolve `a/<app>/f/<field>` tickets via
   `ApplicationTicketResolver`, which applies `authorization.transform(field.value())`.
2. **The caller's identity is available there**: `SessionServiceGrpcImpl.rpcWrapper` opens the
   session's `ExecutionContext` (built `withAuthContext(...)`) around every session gRPC call, so
   `ExecutionContext.getContext().getAuthContext()` returns the caller's `EntraUserAuthContext`
   (Phase 2) inside `transform`.

Policy is data-driven via a **table attribute**: `orders_app.py` publishes each field as
`table.with_attributes({"EntraEntitledRoles": "<comma-separated roles>"})` (this preserves the
`INPUT_TABLE` attribute, so keyed upserts through the published handle keep working):

| Published field | `EntraEntitledRoles` |
| --- | --- |
| `orders` (raw, writable) | `writer` |
| `orders_us` | `trader-us,dh-admin` |
| `orders_emea` | `trader-emea,dh-admin` |
| `orders_all` | `dh-admin` |
| `entitlements` | `dh-admin` |

`transform` rules: non-Entra contexts (SuperUser, server-internal) pass through; Entra users need
a role intersection with the attribute; **fail-closed** for attribute-less objects. Returning
`null` is the engine's documented deny idiom — listings filter silently, direct fetches surface
`NOT_FOUND` (no existence leak).

### Write and console gates

- `InputTableServiceContextualAuthWiring` receives the `AuthContext` **and** the actual tables.
  Subtlety found by the E2E test: `sourceTables` contains both the mutation target *and the
  caller's uploaded batch* — the batch carries no attribute and must be skipped (only tables with
  `Table.INPUT_TABLE_ATTRIBUTE` are checked), otherwise even entitled writers are denied.
- `ConsoleServiceAuthWiring`: `StartConsole` / `ExecuteCommand` / `BindTableToVariable` throw
  `PERMISSION_DENIED` for non-superusers.

## E2E verification without a tenant (2026-07-28)

Infrastructure: the mock-issuer setup from the
[Phase 1 notes](entra-web-login-phase1-as-built.md) (`ENTRA_ISSUER_URI` override + local
discovery/JWKS server), plus RS256 tokens minted with the mock key via `openssl dgst -sign`
(header/payload base64url + signature), fed to the **real Java clients** through the new
`ENTRA_ACCESS_TOKEN` testing hook (skips MSAL; documented in the client README-ENTRA).

| # | Actor (roles) | Action | Result |
| --- | --- | --- | --- |
| 1 | `sim` (`writer`) | Simulator publishes keyed upserts into raw `orders` | ✅ allowed, rows flow |
| 2 | `alice` (`trader-us`) | Subscriber fetches `orders_us` over Barrage | ✅ 170 rows, **US only** |
| 3 | `carol` (`dh-admin` → SuperUser) | Subscriber fetches `orders_all` | ✅ US + EMEA + APAC |
| 4 | `alice` (`trader-us`) | Attempts raw `orders` (simulator path) | ❌ `NOT_FOUND` |
| 5 | `eve` (no roles) | Attempts raw `orders` | ❌ `NOT_FOUND` |

Server audit log showed a matching `Entra login: ... -> user context` /
`Denied table access: ...` line for every event. 14 unit tests
(`EntraOidcAuthenticationHandlerTest` ×10, `EntraEntitlementsTest` ×4) green.

Operational gotcha recorded: `podman compose up --build` can reuse the cached fat-jar `COPY`
layer after a rebuild — use `podman compose build --no-cache` when the jar changed, and verify
with `podman exec ... ls -la /apps/libs/`.

## Still pending (live tenant — Phase 4)

- Real browser sign-in → Authenticator MFA → IDE with role-filtered panels (the full UX).
- Real Entra app-role assignments driving the same enforcement.
- Token lifecycle behavior past expiry (roadmap Phase 3).

## Related

- [ENTRA-IMPLEMENTATION-PLAN.md](ENTRA-IMPLEMENTATION-PLAN.md) — roadmap ("Phase 2b" entry)
- [entra-web-login-phase1-as-built.md](entra-web-login-phase1-as-built.md) — web login plugin + mock-issuer technique
- [`deephaven-entra-oidc-server/README.md`](../../deephaven-entra-oidc-server/README.md) — operator-facing enforcement docs
