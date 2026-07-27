# Custom Entra ID OIDC Handler for Deephaven using MSAL Java

**Date:** 2026-07-27  
**Status:** Research / feasibility notes

## Question

Is it possible to create a custom OIDC authentication handler for Deephaven Community Core that works for both the **Web UI** and **gRPC / Barrage API clients**, using the Microsoft MSAL Java library and talking directly to Microsoft Entra ID?

## Short Answer

**Yes**, with a clear split between what is straightforward and what requires additional work.

| Surface | Feasible? | Notes |
|---------|-----------|-------|
| gRPC / Barrage / Flight (server) | **Yes** | Implement `AuthenticationRequestHandler`; validate JWT against Entra JWKS |
| Java API clients | **Yes** | Use MSAL Java (msal4j) to acquire tokens |
| Web UI | **Yes, but extra work** | Requires a custom JS login plugin (MSAL.js) or alternative login path |
| Full replacement of Keycloak | **Yes** | Achievable once both server handler and browser login exist |

## 1. Server-side handler (gRPC / Barrage / Flight)

Deephaven exposes an open authentication SPI. You implement:

```java
public class EntraOidcAuthenticationHandler implements AuthenticationRequestHandler
```

### Contract

1. The client presents an access token in the handshake / metadata:
   ```
   authenticationTypeAndValue("<handler-name> <access_token>")
   ```
2. Your `login(...)` method receives the token string.
3. You validate it and return an `AuthContext` (or empty → reject).

### MSAL Java vs token validation

| Concern | Library |
|---------|---------|
| **Acquiring** tokens (clients) | **MSAL Java (msal4j)** — recommended |
| **Validating** tokens (server / resource server) | **Not MSAL**. Use Nimbus JOSE + JWT (or equivalent) against Entra’s JWKS endpoint |

MSAL is a client library for obtaining tokens. For resource-server validation you typically:

- Fetch / cache JWKS from  
  `https://login.microsoftonline.com/{tenant}/discovery/v2.0/keys`
- Verify signature, `iss`, `aud` (or `azp`), `exp`, `nbf`
- Extract roles / groups / app roles from claims
- Map them into your `AuthContext` (and later into entitlements)

This is the same pattern the official Keycloak handler uses, just with a different issuer and claim layout.

## 2. Java clients (OrderSimulator, OrderSubscriber, etc.)

Replace the existing `KeycloakTokenClient` with an MSAL-based client:

- **Interactive / user flows** → Public client + Authorization Code + PKCE (or device code flow)
- **Service accounts** → Confidential client + Client Credentials grant

MSAL Java handles token caching, refresh, and Entra-specific behaviour cleanly. The acquired access token is then passed into the Deephaven session exactly as today:

```java
SessionConfig.builder()
    .authenticationTypeAndValue(HANDLER_NAME + " " + accessToken)
    .build();
```

## 3. Web UI

This is the harder surface.

- The published `@deephaven/js-plugin-auth-keycloak` is Keycloak-specific.
- There is no official generic OIDC / Entra plugin for the Community Web UI.

Two realistic options:

| Option | Effort | Description |
|--------|--------|-------------|
| **A. Custom JS login plugin** | Medium–High | Write a plugin that uses **MSAL.js** (or plain OIDC + PKCE) against Entra, then hands the access token to Deephaven the same way the Keycloak plugin does. |
| **B. External login page + token hand-off** | Medium | Host a small login page that performs the Entra flow and redirects back with the token; the IDE consumes it. |

Until a working browser login path exists, the Web IDE cannot authenticate against a pure Entra handler.

## Recommended Implementation Order

1. **Server handler + Java clients**  
   Implement `EntraOidcAuthenticationHandler` (Nimbus/JWKS validation) and an MSAL-based token client. This unblocks all headless and programmatic use.

2. **Web UI**  
   Either keep Keycloak temporarily as a broker for the browser, or invest in a custom MSAL.js login plugin.

3. **Remove Keycloak**  
   Once both the server handler and the browser login path work against Entra directly, Keycloak can be retired.

## Key Technical References

- Deephaven `AuthenticationRequestHandler` interface (see `OidcAuthenticationHandler` in deephaven-core as the existing Keycloak example).
- MSAL Java (msal4j) for token acquisition.
- Nimbus JOSE + JWT (or equivalent) for resource-server JWT validation against Entra JWKS.
- Entra ID OIDC discovery document:  
  `https://login.microsoftonline.com/{tenant}/v2.0/.well-known/openid-configuration`

## Related Documents in This Repository

- [`docs/entra-id-vs-keycloak.md`](../entra-id-vs-keycloak.md) — high-level comparison and broker vs full-replacement options
- [`docs/keycloak-ha-and-operator.md`](../keycloak-ha-and-operator.md) — Keycloak HA notes
- [`docs/oidc/keycloak-entra-broker-architecture.drawio`](keycloak-entra-broker-architecture.drawio) — architecture diagram for the broker approach
