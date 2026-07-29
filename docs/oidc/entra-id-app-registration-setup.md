# Microsoft Entra ID App Registration Setup

> **⚠️ Superseded (2026-07-28):** this early two-app note predates the user sign-in flows, the web
> IDE plugin, and the role-based entitlement enforcement. Follow
> [**entra-tenant-setup-guide.md**](entra-tenant-setup-guide.md) instead — it covers all three
> registrations (`deephaven-api`, `deephaven-users`, `deephaven-pub-sub`) with the exact app-role
> names the code expects (`trader-us`, `trader-emea`, `dh-admin`, `writer` — not
> `Deephaven.Access`).

**Date:** 2026-07-27  
**Purpose:** Configure Entra ID so a confidential client (e.g. OrderSimulator) can obtain access tokens that Deephaven validates with `EntraOidcAuthenticationHandler`.

You will create **two** app registrations:

| App | Role |
|-----|------|
| **deephaven-api** | Resource / audience. Tokens are issued *for* this API. Deephaven validates `aud` against this app. |
| **deephaven-order-simulator** | Confidential client. Simulator uses its ID + secret/cert to get tokens *for* the API. |

```text
Simulator app  --client credentials-->  Entra  --access token (aud=API)-->  Deephaven
```

> **Note:** Entra ID cannot be run locally. Use a free/dev cloud tenant and run Deephaven + clients on your PC.

---

## A. Create the API app (`deephaven-api`)

### A1. Register the app

1. Open [Microsoft Entra admin center](https://entra.microsoft.com) (or Azure Portal → Microsoft Entra ID).
2. **Identity** → **Applications** → **App registrations** → **New registration**.
3. Settings:
   - **Name:** `deephaven-api`
   - **Supported account types:** *Accounts in this organizational directory only* (single tenant)
   - **Redirect URI:** leave empty
4. **Register**.

Copy and save:

- **Application (client) ID** → e.g. `aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee`
- **Directory (tenant) ID** (Overview page) → e.g. `tttttttt-...`

### A2. Set Application ID URI (audience base)

1. Open `deephaven-api` → **Expose an API**.
2. **Application ID URI** → **Add** (or Edit).
3. Suggested value:

   ```text
   api://aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee
   ```

   or a friendly URI if allowed:

   ```text
   api://deephaven-api
   ```

4. **Save**.

This URI (or the client ID, depending on token version) is what should appear in the token’s **`aud`** claim. Use the same value in Deephaven:

```text
-Dauthentication.oidc.entra.audience=api://aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee
```

### A3. Define a scope and/or app role

**Scope** (delegated; still useful for documentation and some flows):

1. **Expose an API** → **Add a scope**.
2. Example:
   - **Scope name:** `access_as_app`
   - **Who can consent:** Admins only
   - **Admin consent display name / description:** “Access Deephaven API as application”
   - **State:** Enabled
3. **Add scope**.

Full scope string becomes:

```text
api://aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee/access_as_app
```

For **client credentials**, Entra uses application permissions. Define an **app role**:

1. `deephaven-api` → **App roles** → **Create app role**.
2. Example:
   - **Display name:** `Deephaven.Access`
   - **Allowed member types:** **Applications**
   - **Value:** `Deephaven.Access`
   - **Description:** “Allows the app to call Deephaven”
   - **Enable** checked
3. **Apply**.

Client-credentials tokens almost always request:

```text
api://aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee/.default
```

`.default` means “all application permissions granted to this client on this API.”

---

## B. Create the simulator app (`deephaven-order-simulator`)

### B1. Register the app

1. **App registrations** → **New registration**.
2. Settings:
   - **Name:** `deephaven-order-simulator`
   - **Supported account types:** single tenant
   - **Redirect URI:** none
3. **Register**.

Copy **Application (client) ID** → this is `ENTRA_CLIENT_ID` for the simulator.

### B2. Client secret or certificate

**Secret (simplest for first test)**

1. `deephaven-order-simulator` → **Certificates & secrets** → **Client secrets** → **New client secret**.
2. Description: `sim-dev`, expiry e.g. 6–12 months.
3. **Add**. Copy the **Value** immediately (only shown once) → `ENTRA_CLIENT_SECRET`.

**Certificate (preferred for production)**

1. Create a key pair / PFX locally (private key stays on the simulator host).
2. **Certificates & secrets** → **Certificates** → **Upload certificate** (**public** `.cer` only).
3. Keep the private key or PFX only on the host / K8s secret — never in git.

### B3. Public client flows

Leave **Allow public client flows** = **No**.  
The simulator is a **confidential** client (secret or cert). Device code / ROPC would use a *different* public app for interactive users (e.g. OrderSubscriber).

---

## C. API permissions + admin consent

### C1. Grant the simulator permission on the API

1. Open **`deephaven-order-simulator`**.
2. **API permissions** → **Add a permission**.
3. **My APIs** → select **`deephaven-api`**.
4. Choose **Application permissions** (not Delegated).
5. Select the app role you created, e.g. **`Deephaven.Access`**.
6. **Add permissions**.

You should see something like:

```text
deephaven-api    Deephaven.Access    Application    Not granted
```

### C2. Admin consent

1. Still on **API permissions**.
2. Click **Grant admin consent for <your tenant>**.
3. Confirm.

Status should become **Granted for <tenant>** with a green check.

Without this step, client-credentials token requests fail with consent errors (`AADSTS65001` or similar).

---

## D. Configuration values

| Variable / setting | Value |
|--------------------|--------|
| `ENTRA_TENANT_ID` | Directory (tenant) ID |
| `ENTRA_CLIENT_ID` | Simulator app client ID |
| `ENTRA_CLIENT_SECRET` | Secret value (if using secret) |
| `ENTRA_SCOPE` | `api://<api-client-id>/.default` |
| Deephaven `issuer-uri` | `https://login.microsoftonline.com/<tenant-id>/v2.0` |
| Deephaven `audience` | Same as Application ID URI, e.g. `api://<api-client-id>` |

Example:

```bash
export AUTH_PROVIDER=entra
export ENTRA_TENANT_ID="tttttttt-tttt-tttt-tttt-tttttttttttt"
export ENTRA_CLIENT_ID="ssssssss-ssss-ssss-ssss-ssssssssssss"   # simulator
export ENTRA_CLIENT_SECRET="the-secret-value"
export ENTRA_SCOPE="api://aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee/.default"

# Deephaven JVM
# -DAuthHandlers=io.deephaven.oidc.entra.EntraOidcAuthenticationHandler
# -Dauthentication.oidc.entra.issuer-uri=https://login.microsoftonline.com/tttt.../v2.0
# -Dauthentication.oidc.entra.audience=api://aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee
```

---

## E. Verify before wiring Deephaven

### E1. Get a token with curl

```bash
curl -s -X POST \
  "https://login.microsoftonline.com/$ENTRA_TENANT_ID/oauth2/v2.0/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "client_id=$ENTRA_CLIENT_ID" \
  -d "client_secret=$ENTRA_CLIENT_SECRET" \
  -d "grant_type=client_credentials" \
  -d "scope=$ENTRA_SCOPE"
```

You should get JSON with `access_token`.

### E2. Inspect the token

Paste `access_token` into [https://jwt.ms](https://jwt.ms) and check:

| Claim | Expected |
|-------|----------|
| `iss` | `https://login.microsoftonline.com/<tenant-id>/v2.0` |
| `aud` | Your Application ID URI or API app id (must match Deephaven `audience`) |
| `appid` / `azp` | Simulator client ID |
| `roles` | May include `Deephaven.Access` if app roles are emitted |

If `aud` does not match what you configured on Deephaven, change either the token audience setup or `-Dauthentication.oidc.entra.audience=...` so they agree.

### E3. Run the simulator

```bash
./gradlew :deephaven-keycloak-oidc-client:runSimulator
```

---

## F. Common mistakes

| Mistake | Result |
|---------|--------|
| Using **Delegated** permission for a daemon | Client credentials will not get that permission |
| Forgetting **admin consent** | Token request fails |
| Scope like `openid` only | Wrong for app-only; use `api://.../.default` |
| Deephaven `audience` ≠ token `aud` | Handler rejects valid Entra tokens |
| Uploading **private** key to Entra | Wrong — upload **public** cert only |

---

## G. Optional: single app registration

You *can* use one app registration as both client and API (expose API on the same app, grant itself app roles). Two apps keep “resource” vs “caller” clearer and match how most production APIs are set up.

---

## Related documents

- [`docs/oidc/custom-entra-oidc-handler-with-msal.md`](custom-entra-oidc-handler-with-msal.md) — handler design, Spring JwtDecoder, MSAL overview
- [`docs/entra-id-vs-keycloak.md`](../entra-id-vs-keycloak.md) — broker vs direct Entra
- [`deephaven-keycloak-oidc-server/README-ENTRA.md`](../../deephaven-keycloak-oidc-server/README-ENTRA.md) — server jar wiring
- [`deephaven-keycloak-oidc-client/README-ENTRA.md`](../../deephaven-keycloak-oidc-client/README-ENTRA.md) — client env vars (on feature branches)
