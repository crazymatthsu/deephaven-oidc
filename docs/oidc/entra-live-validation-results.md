# Entra ID Live Validation Results (Phase 4 complete)

**Dates:** 2026-07-29 → 2026-07-30
**Tenant:** free Microsoft Entra ID tenant `d6c605fc-…` with three app registrations per the
[tenant setup guide](entra-tenant-setup-guide.md): `deephaven-api` (`b73830ed-…`, audience/roles),
`deephaven-users` (`4a53cef2-…`, public client: SPA + desktop platforms), `deephaven-pub-sub`
(`43ce708b-…`, confidential daemon).
**Status:** ✅ **Every tenant-dependent item in the roadmap is now live-verified.** The earlier
mock-issuer results ([enforcement as-built](entra-entitlement-enforcement-as-built.md)) are all
reproduced against real Entra ID.

## What was validated

### 1. Web IDE — enterprise SSO + role enforcement (three personas, three browsers)

Same user account, app-role assignment changed in the portal between runs:

| Persona (roles claim) | IDE console | `orders_us` | `orders_all` / raw `orders` |
|---|---|---|---|
| `dh-admin` → SuperUser | ✅ full console | ✅ | ✅ all tables + entitlement edits |
| `trader-us` → user context | ❌ "restricted to administrators" | ✅ **US rows only** | ❌ `not found` |
| no roles → user context | ❌ | ❌ | ❌ (sees nothing) |

- Sign-in was **silent** (enterprise SSO) whenever the browser held a Microsoft session — no
  password prompt; Authenticator MFA per tenant policy.
- Verified in the embedded test browser, **Safari**, and **Chrome** — including a fully cold
  start on the embed page `/iframe/widget/?name=orders_us` after registering it as an extra SPA
  redirect URI (the reader-persona surface, since the default `/ide` layout leads with the
  console).
- Every accepted login and every denial appeared in the server audit log, e.g.
  `Entra login: user=… roles=[trader-us] -> user context` /
  `Denied table access: user=… roles=[trader-us] entitledRoles=dh-admin`.

### 2. Terminal user sign-in (MSAL, MFA-capable flows)

- **`ENTRA_USER_FLOW=interactive`** — fully verified: system browser opened, silent SSO,
  subscriber authenticated as the signed-in user with `roles=[trader-us]`, picked `orders_us`,
  received the initial snapshot **and live Barrage updates**.
- **`ENTRA_USER_FLOW=devicecode`** — verified to the prompt (URL + one-time code printed,
  Entra accepted the device-code request); sign-in itself was completed via the interactive flow
  instead. No password ever touches the client in either flow.

### 3. Service daemon (`deephaven-pub-sub`, client credentials)

- Authenticated with client ID + secret via MSAL client-credentials; token carried the `writer`
  application role (granted as an application permission on `deephaven-api` + admin consent).
- Publisher connected and streamed keyed upserts into the raw `orders` input table — i.e. the
  **write gate** (`EntraInputTableAuthWiring`) passed for `writer` while browser users without
  the role remained read-only/denied.
- End-to-end pub/sub: the daemon's publishes ticked live into the `trader-us` subscriber and the
  Chrome embed widget simultaneously, each seeing only their entitled slice.

## Issues found live (all fixed and folded into the setup guide's troubleshooting table)

| Issue | Resolution |
|---|---|
| `AADSTS9002326` cross-origin token redemption | IDE redirect URI was registered under a non-SPA platform type → moved to the **Single-page application** section |
| Audience mismatch (`Required audience 'api://…' not present`) | v2 tokens carry the API app's **bare client id** in `aud` → `ENTRA_AUDIENCE` set to the plain GUID |
| `interaction_in_progress` MSAL error | Stale MSAL state after a failed attempt → clear site storage for `localhost:10000` |
| `AADSTS50011` on cold embed-page loads | Plugin uses the current page as redirect URI → registered `http://localhost:10000/iframe/widget/` as an additional SPA redirect URI |
| Role changes flapping (`roles=[]` tokens minutes after assignment) | Entra assignment-propagation lag across token-service replicas (observed up to ~15 min) + browser token caches; converges on its own — retry / clear storage |

## Credential handling convention

Per-client **gitignored** env files under `deephaven-keycloak-oidc-client/`
(`entra-subscriber.env` — no secret; `entra-pubsub.env` — holds the daemon secret), loaded with
`set -a; source <file>; set +a` before the gradle run. The server reads
`deephaven-entra-oidc-server/.env` (also gitignored). No credential is committed.

## What remains (no tenant dependency)

- **Phase 3** — token lifecycle for long-running clients (proactive silent renewal / reconnect)
- **Phase 5 (rest)** — client-side unit tests + GitHub Actions CI
- **Phase 6** — EKS deployment variant (HTTPS redirect URIs replace the localhost ones)

## Related

- [entra-tenant-setup-guide.md](entra-tenant-setup-guide.md) — the portal setup this validated
- [entra-entitlement-enforcement-as-built.md](entra-entitlement-enforcement-as-built.md) — enforcement design + mock-issuer E2E
- [entra-web-login-phase1-as-built.md](entra-web-login-phase1-as-built.md) — web login plugin
- [ENTRA-IMPLEMENTATION-PLAN.md](ENTRA-IMPLEMENTATION-PLAN.md) — overall roadmap
