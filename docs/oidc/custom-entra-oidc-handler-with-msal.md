# Custom Entra ID OIDC Handler for Deephaven

**Date:** 2026-07-27 (updated)  
**Status:** Research / feasibility notes + minimal implementation sketch

## Question

Is it possible to create a custom OIDC authentication handler for Deephaven Community Core that works for both the **Web UI** and **gRPC / Barrage API clients**, talking directly to Microsoft Entra ID?

## Short Answer

**Yes**, with a clear split between what is straightforward and what requires additional work.

| Surface | Feasible? | Notes |
|---------|-----------|-------|
| gRPC / Barrage / Flight (server) | **Yes** | Implement `AuthenticationRequestHandler`; validate JWT against Entra JWKS |
| Java API clients | **Yes** | Use MSAL Java (msal4j) or Spring OAuth2 Client to acquire tokens |
| Web UI | **Yes, but extra work** | Requires a custom JS login plugin (MSAL.js) or alternative login path |
| Full replacement of Keycloak | **Yes** | Achievable once both server handler and browser login exist |

---

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

### Token acquisition vs token validation

| Concern | Recommended library |
|---------|---------------------|
| **Acquiring** tokens (clients) | **MSAL Java (msal4j)** (preferred for Entra) or Spring Security OAuth2 Client |
| **Validating** tokens (server) | **Spring Security OAuth2 Resource Server** (`JwtDecoder`) **or** plain Nimbus JOSE + JWT |

MSAL is a *client* library for obtaining tokens. It is **not** a resource-server validator.

---

## 2. Using Spring Security for token validation

Spring Security provides production-ready OIDC/JWT support that can be used **inside** a Deephaven handler without turning Deephaven into a Spring Boot web application.

| Artifact | Purpose |
|----------|---------|
| `spring-security-oauth2-resource-server` | `JwtDecoder`, validators, claim extraction |
| `spring-security-oauth2-jose` | Nimbus-based JWT support (pulled in transitively) |

You only need the JWT decoding/validation pieces — not the full Spring Security filter chain or `spring-boot-starter-security`.

### Typical setup

```java
JwtDecoder decoder = NimbusJwtDecoder
    .withIssuerLocation("https://login.microsoftonline.com/{tenant}/v2.0")
    .build();

// Optional: enforce audience
OAuth2TokenValidator<Jwt> withAudience =
    new DelegatingOAuth2TokenValidator<>(
        JwtValidators.createDefaultWithIssuer(issuer),
        new JwtClaimValidator<List<String>>("aud",
            aud -> aud != null && aud.contains("api://your-app-id")));
((NimbusJwtDecoder) decoder).setJwtValidator(withAudience);
```

This gives you signature verification, issuer check, expiry, and optional audience/roles validation with very little code.

### Spring vs MSAL vs plain Nimbus

| Concern | Best fit |
|---------|----------|
| Server token validation | **Spring Security Resource Server** or plain Nimbus |
| Java client token acquisition (Entra-specific) | **MSAL Java** (preferred) |
| Java client token acquisition (generic OIDC) | Spring Security OAuth2 Client also excellent |
| Web UI | Still needs JS (MSAL.js / custom plugin) — Spring does not help here |

---

## 3. Minimal `EntraOidcAuthenticationHandler` (Spring `JwtDecoder`)

```java
package io.deephaven.oidc.entra;

import io.deephaven.auth.AuthContext;
import io.deephaven.auth.AuthenticationException;
import io.deephaven.auth.AuthenticationRequestHandler;
import io.deephaven.configuration.Configuration;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * Minimal Deephaven AuthenticationRequestHandler that validates
 * Microsoft Entra ID (Azure AD) access tokens using Spring Security's JwtDecoder.
 *
 * Configuration properties (examples):
 *   authentication.oidc.entra.issuer-uri = https://login.microsoftonline.com/{tenant}/v2.0
 *   authentication.oidc.entra.audience   = api://your-app-client-id   (optional)
 */
public class EntraOidcAuthenticationHandler implements AuthenticationRequestHandler {

    private static final String ISSUER_URI =
            Configuration.getInstance().getProperty("authentication.oidc.entra.issuer-uri");

    // Optional – set if you want to enforce a specific audience / app ID URI
    private static final String AUDIENCE =
            Configuration.getInstance().getProperty("authentication.oidc.entra.audience", "");

    private JwtDecoder jwtDecoder;

    @Override
    public void initialize(String targetUrl) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withIssuerLocation(ISSUER_URI).build();

        // Uncomment / extend to enforce audience if required:
        // OAuth2TokenValidator<Jwt> audienceValidator =
        //     new JwtClaimValidator<>("aud", aud -> aud != null && aud.contains(AUDIENCE));
        // decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
        //     JwtValidators.createDefaultWithIssuer(ISSUER_URI), audienceValidator));

        this.jwtDecoder = decoder;
    }

    @Override
    public String getAuthType() {
        return getClass().getName();   // clients must use this exact string
    }

    @Override
    public Optional<AuthContext> login(long protocolVersion, ByteBuffer payload,
                                       HandshakeResponseListener listener)
            throws AuthenticationException {
        return validate(StandardCharsets.US_ASCII.decode(payload).toString());
    }

    @Override
    public Optional<AuthContext> login(String payload, MetadataResponseListener listener)
            throws AuthenticationException {
        return validate(payload);
    }

    private Optional<AuthContext> validate(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        // Clients may send "Bearer <token>" or just the raw JWT
        String raw = token.startsWith("Bearer ") ? token.substring(7).trim() : token.trim();

        try {
            Jwt jwt = jwtDecoder.decode(raw);

            // Example: extract roles / groups / app roles for later entitlement mapping
            List<String> roles = jwt.getClaimAsStringList("roles");          // app roles
            // List<String> groups = jwt.getClaimAsStringList("groups");     // if configured
            // String oid = jwt.getClaimAsString("oid");                    // object id

            // For a first version we admit any valid Entra token as SuperUser.
            // Replace with a proper AuthContext that carries roles if you need
            // row-level / object-level authorization later.
            return Optional.of(new AuthContext.SuperUser());

        } catch (JwtException e) {
            // Invalid signature, expired, wrong issuer, etc.
            return Optional.empty();
        }
    }
}
```

### Wiring it into Deephaven

```text
-DAuthHandlers=io.deephaven.oidc.entra.EntraOidcAuthenticationHandler
-Dauthentication.oidc.entra.issuer-uri=https://login.microsoftonline.com/{tenant-id}/v2.0
-Dauthentication.oidc.entra.audience=api://your-app-id-uri          # optional
-Dauthentication.client.configuration.list=AuthHandlers,authentication.oidc.entra.issuer-uri
```

Add the Spring jars to the server classpath (e.g. via `EXTRA_CLASSPATH` or the Docker image):

```text
org.springframework.security:spring-security-oauth2-resource-server
org.springframework.security:spring-security-oauth2-jose
```

(Plus their transitive dependencies — Nimbus, etc.)

---

## 4. Java clients (OrderSimulator, OrderSubscriber, …)

Replace the existing `KeycloakTokenClient` with an MSAL-based (or Spring OAuth2 Client) implementation:

- **Interactive / user flows** → Public client + Authorization Code + PKCE (or device code)
- **Service accounts** → Confidential client + Client Credentials grant

Then pass the access token into the Deephaven session:

```java
SessionConfig.builder()
    .authenticationTypeAndValue(
        EntraOidcAuthenticationHandler.class.getName() + " " + accessToken)
    .build();
```

MSAL Java remains the preferred choice for Entra-specific client scenarios.

---

## 5. Web UI

Still the harder surface.

- `@deephaven/js-plugin-auth-keycloak` is Keycloak-specific.
- No official generic OIDC / Entra plugin exists for Community Web UI.

Options:

| Option | Effort | Description |
|--------|--------|-------------|
| Custom JS login plugin | Medium–High | Use MSAL.js (or plain OIDC + PKCE) and hand the access token to Deephaven |
| External login page + token hand-off | Medium | Small page performs Entra login and redirects back with the token |

Until a browser login path exists, the Web IDE cannot authenticate against a pure Entra handler.

---

## Recommended Implementation Order

1. **Server handler + Java clients**  
   Ship `EntraOidcAuthenticationHandler` (Spring `JwtDecoder`) and an MSAL-based token client. Unblocks all headless / programmatic use.

2. **Web UI**  
   Keep Keycloak temporarily as a broker for the browser, *or* build a custom MSAL.js login plugin.

3. **Remove Keycloak**  
   Once both the server handler and the browser login path work against Entra directly.

---

## Key Technical References

- Deephaven `AuthenticationRequestHandler` (see official `OidcAuthenticationHandler` for the Keycloak example)
- Spring Security OAuth2 Resource Server – `NimbusJwtDecoder`, `JwtValidators`
- MSAL Java (msal4j) – token acquisition for clients
- Entra ID OIDC discovery:  
  `https://login.microsoftonline.com/{tenant}/v2.0/.well-known/openid-configuration`

## Related Documents in This Repository

- [`docs/entra-id-vs-keycloak.md`](../entra-id-vs-keycloak.md) — broker vs full-replacement options
- [`docs/keycloak-ha-and-operator.md`](../keycloak-ha-and-operator.md) — Keycloak HA notes
- [`docs/oidc/keycloak-entra-broker-architecture.drawio`](keycloak-entra-broker-architecture.drawio) — architecture diagram for the broker approach
