# Entra ID Tenant Setup Guide — deephaven-api, deephaven-users, deephaven-pub-sub

**Date:** 2026-07-28
**Purpose:** Step-by-step Entra ID configuration for the direct-Entra stack **as currently
implemented** (`scripts/start.sh entra`): server-side token validation + role-based entitlement
enforcement, device-code/interactive user sign-in with Microsoft Authenticator MFA, web IDE SSO,
and the pub/sub daemon. Supersedes the earlier two-app notes in
[entra-id-app-registration-setup.md](entra-id-app-registration-setup.md).

Three registrations — one per identity:

| Registration | OAuth role | Represents |
|---|---|---|
| `deephaven-api` | **Resource** (audience) | The Deephaven server; owns the scope, the app roles, and user→role assignments. Never signs in. |
| `deephaven-users` | **Public client** | Humans: web IDE (SPA) + terminal device-code/interactive sign-in. No secret. |
| `deephaven-pub-sub` | **Confidential client** | The daemon (OrderSimulator publisher / headless subscribers). Client credentials + secret. |

```text
deephaven-users   --device code / SPA PKCE-->  Entra  --token (aud=deephaven-api, roles=[trader-us...])-->  Deephaven
deephaven-pub-sub --client credentials----->  Entra  --token (aud=deephaven-api, roles=[writer])------->  Deephaven
```

> ⚠️ **Role names are contract, not convention.** The demo expects exactly:
> `trader-us`, `trader-emea`, `dh-admin` (humans) and `writer` (daemon). They are wired into
> `orders_app.py` (`EntraEntitledRoles` attributes), `ENTRA_SUPERUSER_ROLES` (default `dh-admin`),
> and the subscriber's view selection. Changing a name means changing it everywhere.
> For the enterprise model (AD groups `DH_ADM`/`DH_READ`/`DH_WRITE` → roles
> `admin`/`reader`/`writer`), see [section 6](#6-enterprise-pattern-windows-sso--ad-groups-dh_adm--dh_read--dh_write).

All steps below: [Entra admin center](https://entra.microsoft.com) → **Identity → Applications →
App registrations** (or Azure Portal → Microsoft Entra ID).

---

## 1. `deephaven-api` — the resource / audience

### 1.1 Register

1. **App registrations → + New registration**
2. Name `deephaven-api`; Supported account types: **Accounts in this organizational directory
   only**; Redirect URI: *leave empty* → **Register**
3. Record from Overview:
   - **Application (client) ID** → `<API_CLIENT_ID>`
   - **Directory (tenant) ID** → `ENTRA_TENANT_ID`

### 1.2 Application ID URI + scope

1. **Expose an API** → next to *Application ID URI* click **Add**, accept `api://<API_CLIENT_ID>`
   → **Save**.
   > **Live-tested note:** with `requestedAccessTokenVersion: 2` (step 1.4), issued tokens carry
   > the **bare client ID** in `aud`, not the `api://…` URI — so set
   > `ENTRA_AUDIENCE=<API_CLIENT_ID>` (plain GUID). The server log tells you immediately when
   > this is wrong: `Entra login rejected: ... Required audience '...' not present in token`.
2. **+ Add a scope**:
   - Scope name: `access_as_user`
   - Who can consent: *Admins and users*
   - Admin consent display name: `Access Deephaven as the signed-in user`
   - Admin consent description: `Allows the app to call the Deephaven API on behalf of the signed-in user.`
   - State: Enabled → **Add scope**
3. The full scope `api://<API_CLIENT_ID>/access_as_user` is `ENTRA_SCOPE` (users) and
   `ENTRA_WEB_SCOPE` (web IDE).

### 1.3 App roles (exact names!)

**App roles → + Create app role**, four times:

| Display name | Allowed member types | Value | Description |
|---|---|---|---|
| Trader US | **Users/Groups** | `trader-us` | Sees US orders (`orders_us`) |
| Trader EMEA | **Users/Groups** | `trader-emea` | Sees EMEA orders (`orders_emea`) |
| Deephaven Admin | **Users/Groups** | `dh-admin` | Superuser: all tables, console, entitlement edits |
| Writer | **Applications** | `writer` | May publish into the raw `orders` input table |

(Enable each; **Value** is what lands in the token's `roles` claim — it must match the table above.)

### 1.4 Force v2 access tokens

**Manifest** → find `"api"` / `requestedAccessTokenVersion` and set it to `2` → **Save**.
Required so tokens carry `iss = https://login.microsoftonline.com/<tenant>/v2.0`, which the
server's `issuer-uri` validates. (In the newer manifest editor this may appear as
`accessTokenAcceptedVersion` — same setting.)

### 1.5 Assign users to roles

1. **Identity → Applications → Enterprise applications** → `deephaven-api` → **Users and groups**
2. **+ Add user/group** per test user:
   - alice → *Trader US*
   - bob → *Trader EMEA*
   - carol → *Deephaven Admin*

(No optional claims or group claims needed — assigned app roles appear in the `roles` claim
automatically. On P1-licensed tenants you may assign security groups to roles instead of users.)

### 1.6 MFA (Microsoft Authenticator)

- Free tenant: **Identity → Overview → Properties → Manage security defaults** → enabled (default
  on new tenants) — this enforces MFA registration.
- Each test user signs in once at https://aka.ms/mfasetup and registers the Authenticator app.

---

## 2. `deephaven-users` — the human sign-in client (public, no secret)

### 2.1 Register

1. **+ New registration**; Name `deephaven-users`; single tenant; Redirect URI empty → **Register**
2. Record **Application (client) ID** → used as **both** `ENTRA_CLIENT_ID` (device
   code/interactive) and `ENTRA_SPA_CLIENT_ID` (web IDE).

### 2.2 Platforms (two on the same app)

**Authentication → + Add a platform**:

1. **Mobile and desktop applications** → custom redirect URI `http://localhost` → Configure.
   (Used by the `interactive` flow; the default `devicecode` flow needs no redirect URI.)
2. **+ Add a platform** again → **Single-page application** → redirect URI
   `http://localhost:10000/ide/` → Configure.
   ⚠️ Must match the IDE URL **exactly, including the trailing slash** (the login plugin computes
   `window.location.origin + pathname`). Add your production HTTPS URL here later — Entra allows
   plain `http` only for localhost.

### 2.3 Enable device code

Authentication → **Advanced settings → Allow public client flows = Yes** → Save.
(Missing this ⇒ `AADSTS7000218` on device-code sign-in.)

### 2.4 API permission

1. **API permissions → + Add a permission → My APIs** (not Microsoft APIs) → `deephaven-api`
2. **Delegated permissions** → tick `access_as_user` → **Add permissions**
3. **Grant admin consent for `<tenant>`** → status shows a green *Granted* check.

### 2.5 Do NOT

- create any client secret or certificate on this app (it's a public client);
- enable ROPC-anything — the MFA-capable flows don't need it.

---

## 3. `deephaven-pub-sub` — the daemon client (confidential)

### 3.1 Register

1. **+ New registration**; Name `deephaven-pub-sub`; single tenant; no redirect URI → **Register**
2. Record **Application (client) ID** → the simulator's `ENTRA_CLIENT_ID`.

### 3.2 Client secret

1. **Certificates & secrets → Client secrets → + New client secret**; description `pubsub-dev`,
   expiry 6–12 months → **Add**
2. Copy the **Value** immediately (shown once) → `ENTRA_CLIENT_SECRET`. Keep it in your shell env
   or the gitignored `.env` — never in git or chat. (Production: prefer uploading a **public**
   certificate instead and keeping the private key on the host.)

### 3.3 Application permission (`writer` role)

1. **API permissions → + Add a permission → My APIs** → `deephaven-api`
2. **Application permissions** (not Delegated — client credentials cannot use delegated scopes)
   → tick **Writer** (`writer`) → **Add permissions**
3. **Grant admin consent for `<tenant>`** → green check.

At runtime the daemon requests `ENTRA_SCOPE="api://<API_CLIENT_ID>/.default"` — `.default`
resolves to all admin-consented application permissions, so the token's `roles` = `["writer"]`.

Leave **Allow public client flows = No** on this app.

---

## 4. Wire the values into the project

Server (`deephaven-entra-oidc-server/.env`, from `.env.example`; gitignored):

```bash
ENTRA_TENANT_ID=<tenant id>
ENTRA_AUDIENCE=<API_CLIENT_ID>                  # bare GUID — see the note in 1.2
ENTRA_SUPERUSER_ROLES=dh-admin
ENTRA_SPA_CLIENT_ID=<deephaven-users client id>
ENTRA_WEB_SCOPE=api://<API_CLIENT_ID>/access_as_user   # scope keeps the api:// form
```

Subscriber (human, device code + Authenticator — no password):

```bash
export AUTH_PROVIDER=entra ENTRA_TENANT_ID=<tenant id> \
  ENTRA_CLIENT_ID=<deephaven-users client id> \
  ENTRA_SCOPE="api://<API_CLIENT_ID>/access_as_user"
```

Simulator (daemon):

```bash
export AUTH_PROVIDER=entra ENTRA_TENANT_ID=<tenant id> \
  ENTRA_CLIENT_ID=<deephaven-pub-sub client id> \
  ENTRA_CLIENT_SECRET=<secret value> \
  ENTRA_SCOPE="api://<API_CLIENT_ID>/.default"
```

---

## 5. Verify (before blaming Deephaven)

1. **Daemon token via curl:**

   ```bash
   curl -s -X POST "https://login.microsoftonline.com/$ENTRA_TENANT_ID/oauth2/v2.0/token" \
     -d "client_id=$ENTRA_CLIENT_ID" -d "client_secret=$ENTRA_CLIENT_SECRET" \
     -d "grant_type=client_credentials" -d "scope=$ENTRA_SCOPE"
   ```

2. **Decode** the `access_token` at https://jwt.ms and check:

   | Claim | Expected |
   |---|---|
   | `iss` | `https://login.microsoftonline.com/<tenant>/v2.0` (if v1 `sts.windows.net` → step 1.4 missed) |
   | `aud` | exactly your `ENTRA_AUDIENCE` (if it's the bare GUID instead of `api://…`, set `ENTRA_AUDIENCE` to the GUID — the handler must match what tokens actually carry) |
   | `roles` | `["writer"]` (missing → step 3.3 permission or consent missed) |
   | `azp` | deephaven-pub-sub client id |

3. **Live end-to-end** (the project's Phase 4 script):

   ```bash
   scripts/start.sh entra
   ./gradlew :deephaven-keycloak-oidc-client:runSimulator     # daemon publishes
   ./gradlew :deephaven-keycloak-oidc-client:runSubscriber    # device code → Authenticator push → US rows only (alice)
   # browser: http://localhost:10000/ide → Entra sign-in / silent SSO → role-filtered panels
   ```

   Expected enforcement (same matrix the mock-issuer E2E proved, see
   [entra-entitlement-enforcement-as-built.md](entra-entitlement-enforcement-as-built.md)):
   alice sees `orders_us` only and gets NOT_FOUND on `orders`; carol sees everything + console;
   non-admin console access is PERMISSION_DENIED.

---

## 6. Enterprise pattern: Windows SSO + AD groups (DH_ADM / DH_READ / DH_WRITE)

The sections above use per-user role assignments and demo region roles — right for a free test
tenant. In the enterprise, users already sign into Windows with corporate accounts, and access is
requested through **AD group membership**. This maps onto the same machinery with zero code
changes — roles are data everywhere in this project.

### 7.1 Role model

| AD group | App role **value** on `deephaven-api` | Deephaven meaning |
|---|---|---|
| `DH_ADM` | `admin` | Superuser: all tables, console, entitlement edits (`ENTRA_SUPERUSER_ROLES=admin`) |
| `DH_READ` | `reader` | May fetch tables whose `EntraEntitledRoles` attribute includes `reader` |
| `DH_WRITE` | `writer` | May publish into input tables entitled to `writer` (same value the pub-sub daemon uses) |

Create these as app roles on `deephaven-api` exactly as in step 1.3 — *Allowed member types* =
**Users/Groups** for `admin`/`reader`, and **Both** for `writer` (humans in `DH_WRITE` *and* the
`deephaven-pub-sub` service principal share it). The demo's region roles can coexist during a
transition or be deleted once unused.

### 7.2 Get the AD groups into Entra

- Hybrid AD: sync `DH_ADM`/`DH_READ`/`DH_WRITE` via **Entra Connect / Cloud Sync** — they must be
  **security groups** (distribution lists don't carry into tokens/assignments). They appear in
  Entra as synced security groups.
- Cloud-only tenants: create them directly as security groups.

### 7.3 Assign the groups to the app roles

**Enterprise applications → `deephaven-api` → Users and groups → + Add user/group**, three times:
`DH_ADM` → *admin*, `DH_READ` → *reader*, `DH_WRITE` → *writer*.

Two important caveats:

- **Licensing:** assigning *groups* (rather than individual users) to app roles requires
  **Entra ID P1** — standard in enterprises, absent on a bare free tenant (where you assign users
  directly, as in 1.5).
- **Nesting is NOT expanded:** app-role assignment applies to **direct members only**. A user in
  `SubGroup ⊂ DH_READ` gets no `reader` claim. Keep the `DH_*` groups flat (direct user members),
  which also matches the "users request to be added" workflow.

No group claims / optional claims configuration is needed (or wanted): membership surfaces as the
app-role **value** (`reader`, not a group GUID) in the token's `roles` claim, exactly like a
direct assignment — so the server, the entitlement attributes, and the clients see identical
shapes in demo and enterprise setups.

### 7.4 Project configuration for this model

Server `.env`:

```bash
ENTRA_SUPERUSER_ROLES=admin
```

Table policy (`orders_app.py` — attribute values are free-form strings):

```python
_app["orders"]       = _entitled(orders, "writer")          # raw feed: writers only
_app["entitlements"] = _entitled(entitlements, "admin")
_app["orders_us"]    = _entitled(orders_us, "reader,admin") # readers see the curated views
_app["orders_emea"]  = _entitled(orders_emea, "reader,admin")
_app["orders_all"]   = _entitled(orders_all, "reader,admin")
```

(Finer-grained read tiers later — e.g. regional read groups — are just more AD groups + app roles
+ attribute entries; the enforcement code never changes.)

One demo-specific note: `OrderSubscriber` picks its Barrage view from the region roles
(`VIEW_BY_ROLE`); under the flat `reader` model, update that map (e.g. `reader` → `orders_all`)
or treat view choice as application logic — the *server-side* enforcement above is independent of
it.

### 7.5 What users experience

1. User requests membership of `DH_READ` via the existing AD/IGA process (or an Entra ID
   Governance access package).
2. Membership syncs to Entra (Entra Connect cycle, typically ≤30 min; immediate for cloud groups).
3. Next sign-in: from a domain-/Entra-joined Windows machine the browser signs into the Deephaven
   IDE **silently** (Primary Refresh Token SSO — no password), Conditional Access injects the
   Authenticator MFA prompt per policy, and the issued token carries `roles=["reader"]`.
4. Role changes take effect on the **next token issuance** — sign out/in, or at silent renewal
   (access tokens live ~60–90 min). Removal from a group likewise takes until the current token
   expires; set shorter token lifetimes if revocation latency matters.

## 7. Common mistakes

| Symptom | Cause |
|---|---|
| `AADSTS7000218` on device code | *Allow public client flows* not enabled on `deephaven-users` (2.3) |
| `AADSTS65001` (consent) | Admin consent not granted (2.4 / 3.3) |
| `AADSTS500011` (resource not found) | Scope requested doesn't match the Application ID URI (1.2) |
| `AADSTS900023` | Wrong/typo'd tenant id |
| `AADSTS50076` | You're using the legacy `ropc` flow against an MFA account — use `devicecode` |
| Handler rejects valid-looking tokens | `aud` ≠ `ENTRA_AUDIENCE` (see 5.2) or v1 issuer (1.4) |
| `roles` claim empty for a user | User not assigned to an app role on `deephaven-api`'s **Enterprise application** (1.5) |
| SPA redirect error in browser | Redirect URI ≠ `http://localhost:10000/ide/` exactly (2.2) |
| `AADSTS9002326` (cross-origin token redemption) after successful sign-in | The IDE redirect URI is registered under the **wrong platform type** (Web / Mobile & desktop) — it must be under **Single-page application** (2.2). Hit live: same URI, wrong section |
| `interaction_in_progress` MSAL error after fixing registration | Stale MSAL state from the failed attempt — clear the site's local/session storage for `localhost:10000` and reload |
| `AADSTS50011` when opening an **embed page** (`/iframe/widget/...`) cold | The login plugin uses the current page as redirect URI. Register `http://localhost:10000/iframe/widget/` as an additional SPA redirect URI, or sign in once at `/ide/` first (embeds then reuse the cached token) |
| Role change seems ignored for a minute or two | Two causes: the browser's cached access token (~60–90 min; clear site storage to force re-issue) and Entra's own assignment-propagation lag (observed live: one stale-role token issued seconds after a change) |
| Daemon has no `roles` | Application permission added but consent missed, or role's member type doesn't include *Applications* (1.3/3.3) |

## Related

- [ENTRA-IMPLEMENTATION-PLAN.md](ENTRA-IMPLEMENTATION-PLAN.md) — roadmap (this guide fulfils Phase 4)
- [entra-entitlement-enforcement-as-built.md](entra-entitlement-enforcement-as-built.md) — what the server enforces
- [entra-web-login-phase1-as-built.md](entra-web-login-phase1-as-built.md) — web IDE login plugin
- [`deephaven-entra-oidc-server/README.md`](../../deephaven-entra-oidc-server/README.md) / [`deephaven-keycloak-oidc-client/README-ENTRA.md`](../../deephaven-keycloak-oidc-client/README-ENTRA.md) — runtime configuration
