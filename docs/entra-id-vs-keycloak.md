# Microsoft Entra ID vs Keycloak for Deephaven OIDC

**Date:** 2026-07-25  
**Context:** Research on replacing or federating Keycloak with Microsoft Entra ID (formerly Azure AD) in this repository and in Deephaven Community Core.

## Summary

Deephaven Community Core does **not** ship first-class Microsoft Entra ID support. The official OIDC authentication provider and the browser login plugin are intentionally Keycloak-specific. This is a product/scope decision, not a hard technical limitation.

## Why Deephaven Community Does Not Support Entra ID Natively

From the official Deephaven Core OIDC provider README:

> “For the sake of **simplicity**, the server and client both today assume that the OpenID Connect server is **Keycloak**, but the server component is designed using Pac4j, making it flexible enough to support not only general OpenID Connect services, but also other kinds of single-sign on services like SAML, OAuth, etc.”

### Root causes

| Factor | Explanation |
|--------|-------------|
| **Simplicity for Community** | Keycloak is free, self-hostable, and easy to demo in Docker. It gives Community users a complete working example without requiring a cloud IdP. |
| **Reference implementation choice** | Keycloak was selected as the single reference IdP. The shipped `OidcAuthenticationHandler` hard-codes `KeycloakOidcClient` / `KeycloakOidcConfiguration` and Keycloak-prefixed properties (`authentication.oidc.keycloak.*`). |
| **Browser plugin** | `@deephaven/js-plugin-auth-keycloak` only knows how to talk to Keycloak. A polished generic or Entra-specific plugin was never published. |
| **Product differentiation** | Richer enterprise identity integrations (including polished SAML and multi-IdP support) are part of Deephaven Enterprise. |
| **Underlying technology is capable** | The server uses Pac4j, which can support generic OIDC (including Entra ID’s discovery endpoint and JWKS). The limitation is in the shipped configuration and JS plugin, not the core engine. |

There is no public statement that Deephaven is philosophically opposed to Entra ID. It is a prioritization and scope decision for the open-source edition.

## Practical Options for This Repository

### Option A – Keycloak as a Broker (Recommended)

Keep the current Deephaven + Keycloak stack and federate Entra ID into Keycloak.

**Pros**
- Zero (or near-zero) code changes on the Deephaven side
- Users get true Entra SSO
- Existing row-level security, clients, and EKS design continue to work
- Matches the guidance already present in `deploy/eks/DESIGN.md`

**How**
1. In Keycloak Admin Console → Identity Providers → Add “OpenID Connect” or “Microsoft”
2. Point it at your Entra tenant
3. Map Entra groups / app roles → Keycloak realm roles (`trader-us`, `trader-emea`, `dh-admin`, `writer`)
4. Optionally disable direct Keycloak username/password login

### Option B – Full Replacement with Entra ID

Remove Keycloak and point Deephaven directly at Entra ID.

This requires significant work:

| Component | Required Change |
|-----------|-----------------|
| Server auth handler | Replace or fork `OidcAuthenticationHandler` to use generic Pac4j `OidcClient` + Entra discovery URL (`https://login.microsoftonline.com/{tenant}/v2.0/.well-known/openid-configuration`) |
| Configuration properties | Stop using `authentication.oidc.keycloak.*`; expose generic OIDC properties |
| Browser login | Replace `@deephaven/js-plugin-auth-keycloak` with a custom or generic OIDC login plugin |
| Java clients (`KeycloakTokenClient`) | Replace with MSAL4J or a plain HTTP client against Entra’s token endpoint |
| Role / claim extraction | Entra does not put roles under `realm_access.roles`. Typical locations are `roles`, `groups`, or custom claims |
| Docker / Compose / EKS | Remove Keycloak service and all related manifests |
| App registration | Create public + confidential clients in Entra ID with correct redirect URIs |

## Recommendation

For almost all production use cases, **Option A (Keycloak as broker)** is the pragmatic choice. It delivers Entra SSO with minimal risk and keeps the rest of the architecture intact.

Only pursue Option B if there is a hard requirement to eliminate Keycloak entirely.

## Related Files in This Repo

- `README.md` – current Keycloak-centric design
- `deploy/eks/DESIGN.md` – already mentions Entra ID brokering
- `deephaven-keycloak-oidc-common/.../KeycloakTokenClient.java` – token acquisition and role extraction
- `deephaven-keycloak-oidc-server/docker/deephaven/Dockerfile` – Keycloak JS plugin patches
