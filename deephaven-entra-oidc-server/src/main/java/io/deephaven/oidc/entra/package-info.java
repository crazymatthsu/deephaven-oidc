/**
 * Custom Deephaven authentication handler for Microsoft Entra ID (Azure AD).
 *
 * <p>Primary type: {@link io.deephaven.oidc.entra.EntraOidcAuthenticationHandler}.
 *
 * <p>Uses Spring Security OAuth2 Resource Server ({@code JwtDecoder}) for concise,
 * production-grade JWT validation against Entra's JWKS endpoint. Intended for gRPC /
 * Barrage / Flight clients. Web UI login still requires a separate browser plugin or
 * Keycloak broker (see {@code docs/oidc/custom-entra-oidc-handler-with-msal.md}).
 */
package io.deephaven.oidc.entra;
