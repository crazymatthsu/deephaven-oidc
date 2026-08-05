# SAML vs OIDC — Protocol Comparison

A clear comparison of **SAML 2.0** and **OpenID Connect (OIDC)** for authentication and single sign-on.

---

## Side-by-Side Comparison

| Aspect | **SAML 2.0** | **OIDC (OpenID Connect)** |
|--------|--------------|---------------------------|
| **Full Name** | Security Assertion Markup Language | OpenID Connect |
| **Underlying Protocol** | XML-based, built on top of SOAP/HTTP | Built on top of **OAuth 2.0** + JWT |
| **Primary Purpose** | Authentication + Authorization (enterprise SSO) | Authentication (identity layer on OAuth 2.0) |
| **Token / Assertion Format** | **XML** (SAML Assertions) | **JSON Web Tokens (JWT)** |
| **Message Size** | Large (verbose XML) | Compact (JSON) |
| **Typical Use Case** | Enterprise / B2B / legacy corporate SSO | Modern web, mobile, SPA, APIs, cloud-native apps |
| **Client Types** | Mostly browser-based (web apps) | Web, SPA, mobile, native, machine-to-machine |
| **Flow Style** | Browser redirect (POST or Redirect binding) | Multiple flows: Auth Code + PKCE, Implicit (legacy), Device Code, Client Credentials, etc. |
| **Mobile / SPA Friendliness** | Poor | Excellent |
| **API / Microservices** | Awkward | Native (access tokens are designed for APIs) |
| **Standardization Body** | OASIS | OpenID Foundation |
| **Maturity** | Very mature (2005) | Modern (2014, actively evolved) |
| **Complexity** | High (XML signatures, certificates, metadata exchange) | Lower (JSON, JWKS, simpler libraries) |
| **Library Ecosystem** | Older / heavier | Modern, lightweight (MSAL, Auth0, Keycloak, Spring Security, etc.) |

---

## Key Technical Differences

### 1. Token Format

- **SAML**: XML Assertion containing claims about the user. Signed with XML Digital Signature.
- **OIDC**: JWT (Header.Payload.Signature). Easy to parse, validate, and pass around. Can contain ID Token + Access Token + Refresh Token.

### 2. Protocol Stack

- SAML is a complete authentication protocol by itself.
- OIDC is an **identity layer** on top of OAuth 2.0. This is why OIDC is so powerful for APIs — you get both authentication *and* delegated authorization in one design.

### 3. Flows

- SAML mainly uses browser redirects (HTTP-POST or HTTP-Redirect bindings).
- OIDC offers multiple modern flows, especially **Authorization Code + PKCE** (recommended for SPAs and public clients) and **Device Code** (great for CLI/headless).

### 4. Metadata & Discovery

- SAML: Manual metadata XML exchange between IdP and SP.
- OIDC: Automatic discovery via `/.well-known/openid-configuration` + JWKS endpoint. Much easier to configure.

---

## Pros & Cons

### SAML

**Pros**
- Extremely mature and widely supported in large enterprises
- Strong support for complex enterprise federation scenarios
- Good for pure web applications with existing SAML infrastructure

**Cons**
- Verbose and complex (XML, certificates, signature validation)
- Poor fit for mobile apps, SPAs, and APIs
- Harder to debug
- Declining for new green-field projects

### OIDC

**Pros**
- Lightweight, modern, and developer-friendly
- Excellent for web + mobile + SPA + APIs
- Built-in support for refresh tokens, silent renewal, and fine-grained scopes
- Works extremely well with Entra ID, Auth0, Okta, Keycloak, etc.
- Better security practices for public clients (PKCE)

**Cons**
- Slightly less “enterprise legacy” coverage than SAML in some old systems
- Requires understanding of OAuth 2.0 concepts

---

## Which One Should You Use in 2026?

| Scenario | Recommended |
|----------|-------------|
| New web / SPA / mobile application | **OIDC** |
| API / microservices authentication | **OIDC** |
| Existing large enterprise with heavy SAML investment | SAML (or hybrid) |
| Microsoft Entra ID integration (modern) | **OIDC** (preferred) |
| Deephaven Community (custom handler) | **OIDC** |
| Deephaven Enterprise official path | SAML |

---

## Bottom Line

- **SAML** = the old enterprise standard (XML, browser-centric, mature but heavy).
- **OIDC** = the modern standard (JSON/JWT, flexible, mobile & API friendly, built on OAuth 2.0).

For almost all new projects in 2026 — especially anything involving Entra ID, SPAs, mobile clients, or APIs — **OIDC is the clear winner**. SAML is mainly kept for backward compatibility with older enterprise systems.
