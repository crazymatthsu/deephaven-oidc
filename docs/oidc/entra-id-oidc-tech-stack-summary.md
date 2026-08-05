# Entra ID OIDC Tech Stack & Components Summary

This document summarizes the technology stack and components implemented for **direct Microsoft Entra ID (Azure AD) OIDC authentication** in Deephaven Community Core.

> **Note:** This summary focuses exclusively on the direct Entra ID path. The Keycloak implementation is out of scope.

**Status:** Live-verified against a real Entra tenant (web IDE SSO + Microsoft Authenticator MFA, role-enforced reads/writes, device-code / interactive / client-credentials flows).

---

## 1. Core Technology Stack

| Layer | Technology | Purpose |
|-------|------------|---------|
| **Identity Provider** | Microsoft Entra ID (Azure AD) | Token issuer, Conditional Access, Microsoft Authenticator MFA, App Roles / Groups |
| **Server Runtime** | Deephaven Community Core `0.39.4` (`ghcr.io/deephaven/server`) | Base engine + Flight / Barrage / gRPC |
| **JWT Validation** | Spring Security OAuth2 Resource Server + JOSE (`6.4.2`) + NimbusJwtDecoder | Validates Entra access tokens against tenant JWKS (issuer + optional audience) |
| **Custom Auth Handler** | `EntraOidcAuthenticationHandler` | Deephaven `AuthenticationRequestHandler` SPI implementation |
| **Authorization** | Custom Dagger server assembly (`EntraServerMain` + `EntraAuthorizationProvider`) | Server-side entitlement enforcement (not just script-level filtering) |
| **Java Token Client** | MSAL4J `1.25.0` | Client-credentials, Device Code, Interactive (auth-code + PKCE), silent renewal |
| **Browser / Web IDE** | MSAL.js (`@azure/msal-browser`) + custom JS plugin | Auth-code + PKCE redirect flow with enterprise SSO + MFA |
| **Build** | Gradle (multi-module, Kotlin DSL) + fatJar | Self-contained handler jar for `EXTRA_CLASSPATH` |
| **Container** | Multi-stage Docker (Node 20 for JS plugin + Deephaven base) | Bakes fat jar, JS plugin, and swaps main class to `EntraServerMain` |
| **Local Orchestration** | Podman Compose + nginx (TLS terminator on :1433) | HTTPS required by Entra for non-localhost redirect URIs |
| **Production Deploy** | AWS EKS + ALB (gRPC-aware) + ACM | No in-cluster IdP; pure Entra |

---

## 2. Implemented Components

### A. Authentication (Server Side)

**`EntraOidcAuthenticationHandler`** (`deephaven-entra-oidc-server`)

- Uses `NimbusJwtDecoder.withIssuerLocation(...)` against `https://login.microsoftonline.com/<tenant>/v2.0`
- Optional audience validation (`aud` or `azp`)
- Maps token claims → Deephaven `AuthContext`:
  - **Superuser** if the token contains any of `authentication.oidc.entra.superuser-roles` (default `dh-admin`)
  - Otherwise → `EntraUserAuthContext` carrying:
    - `oid` (stable object ID)
    - username (`preferred_username` / `upn` / app id for service principals)
    - roles (`roles` claim preferred, fallback to `groups`)
- Configuration via system properties or environment variables (`AUTHENTICATION_OIDC_ENTRA_*`)

### B. Authorization / Entitlement Enforcement (Server Side)

This is the key differentiator from the open-source baseline. A custom server assembly (following Deephaven’s official `server/jetty-app-custom` pattern) replaces the default allow-all authorization:

| Component | Responsibility |
|-----------|----------------|
| `EntraServerMain` | Entry point; builds Dagger component with custom authorization |
| `EntraAuthorizationProvider` | Extends `AllowAllAuthorizationProvider`; wires the three hooks below |
| `EntraTicketAuthorization` | Universal chokepoint for **every** table access path (Flight DoGet, Barrage subscriptions, web IDE, session exports). Tables carry a `EntraEntitledRoles` attribute. Non-superusers are denied (returns `null` → NOT_FOUND / filtered listing) unless they hold a matching role. |
| `EntraInputTableAuthWiring` | Input-table writes require an entitled role (demo uses `writer` on the raw `orders` table) |
| `EntraConsoleAuthWiring` | `StartConsole` / `ExecuteCommand` / `BindTableToVariable` restricted to superusers only |
| `EntraEntitlements` | Shared policy helpers (`ENTITLED_ROLES_ATTRIBUTE`, role intersection, fail-closed behavior) |

Policy is **fail-closed** for entitlement-scoped users.

### C. Client-Side Token Acquisition

**`EntraTokenClient`** (MSAL4J) in the shared common module:

| Flow | Use Case | MFA Support |
|------|----------|-------------|
| Client Credentials | Daemon / OrderSimulator (confidential client) | N/A (service principal) |
| Device Code (default) | Interactive Java clients | Full (Microsoft Authenticator) |
| Interactive (auth-code + PKCE) | Desktop browser flow | Full |
| Silent renewal | Token refresh via MSAL cache | Avoids re-prompt while refresh token is valid |

Also includes a helper to extract roles from the access token for client-side view selection.

### D. Web IDE Login Plugin

**`js-plugin-auth-entra`**

- Built with Vite → single self-contained CommonJS bundle
- MSAL.js `PublicClientApplication` (auth-code + PKCE)
- Redirects to Entra → enterprise SSO + Conditional Access MFA
- Hands the access token to Deephaven using the custom handler type (`io.deephaven.oidc.entra.EntraOidcAuthenticationHandler`)
- Baked into the Docker image under `/opt/deephaven/config/js-plugins/`

### E. Demo Application & Views

Application-mode Python script (`orders_app.py`) publishes:

- Keyed input table `orders`
- `entitlements` table
- Role-filtered views (`orders_us`, `orders_emea`, `orders_all`) that carry the `EntraEntitledRoles` attribute used by the authorization layer

### F. Deployment Surfaces

| Environment | Details |
|-------------|---------|
| **Local** | `scripts/start.sh entra` + Podman Compose (Deephaven + nginx TLS front on :1433) |
| **EKS** | `deploy/eks-entra/` — pure Entra (no Keycloak), ConfigMap-driven, single ALB ingress |

---

## 3. Authentication Flows Supported

| Client Type | Flow | MFA Support | Notes |
|-------------|------|-------------|-------|
| Web IDE (browser) | Auth-code + PKCE (MSAL.js) | Full (Authenticator) | Enterprise SSO / silent sign-on |
| Java interactive | Device Code (default) or Interactive | Full | Headless-friendly |
| Java daemon / simulator | Client Credentials | N/A | Service principal |

---

## 4. Key Design Decisions

- **No reliance on the official `deephaven-oidc-authentication-provider`** (pac4j). A custom Spring Security-based handler provides tighter control and Entra-specific claim mapping.
- **True server-side enforcement** of row-level security via a custom `AuthorizationProvider` + table attributes. This closes the open-source gap where any authenticated user could otherwise fetch all data.
- **Fat-jar design** so the handler + all Spring/Nimbus dependencies can be dropped onto any Deephaven server via `EXTRA_CLASSPATH`.
- Superuser vs regular user separation is driven purely by Entra App Roles (or groups).
- The Docker image swaps the launch class from `JettyMain` to `EntraServerMain` so the custom authorization assembly is used by default.

---

## Related Documentation

- [Entra Implementation Plan](ENTRA-IMPLEMENTATION-PLAN.md)
- [Custom Entra OIDC Handler with MSAL](custom-entra-oidc-handler-with-msal.md)
- [Entra Tenant Setup Guide](entra-tenant-setup-guide.md)
- [Entra ID App Registration Setup](entra-id-app-registration-setup.md)
- [Entra Entitlement Enforcement (As-Built)](entra-entitlement-enforcement-as-built.md)
- [Entra Live Validation Results](entra-live-validation-results.md)
- [EKS Deployment (Entra variant)](../../deploy/eks-entra/README.md)
