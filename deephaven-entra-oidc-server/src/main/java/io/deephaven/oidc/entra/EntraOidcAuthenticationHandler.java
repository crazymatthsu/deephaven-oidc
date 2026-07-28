package io.deephaven.oidc.entra;

import io.deephaven.auth.AuthContext;
import io.deephaven.auth.AuthenticationException;
import io.deephaven.auth.AuthenticationRequestHandler;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Deephaven {@link AuthenticationRequestHandler} that validates Microsoft Entra ID (Azure AD)
 * access tokens using Spring Security's {@link JwtDecoder}.
 *
 * <p>Clients present a bearer token as:
 * <pre>
 *   authenticationTypeAndValue(
 *       "io.deephaven.oidc.entra.EntraOidcAuthenticationHandler <access_token>")
 * </pre>
 *
 * <h2>Required configuration</h2>
 * <ul>
 *   <li>{@code authentication.oidc.entra.issuer-uri} — e.g.
 *       {@code https://login.microsoftonline.com/<tenant-id>/v2.0}</li>
 * </ul>
 *
 * <h2>Optional configuration</h2>
 * <ul>
 *   <li>{@code authentication.oidc.entra.audience} — expected {@code aud} / app ID URI
 *       (e.g. {@code api://your-app-id}). When set, tokens without this audience are rejected.</li>
 * </ul>
 *
 * <p>Values may be supplied as Java system properties ({@code -D...}) or environment variables
 * ({@code AUTHENTICATION_OIDC_ENTRA_ISSUER_URI}, {@code AUTHENTICATION_OIDC_ENTRA_AUDIENCE}).
 *
 * <h2>Classpath</h2>
 * Place this module's jar <em>and</em> its Spring Security transitive dependencies on the
 * Deephaven server {@code EXTRA_CLASSPATH}, then set:
 * <pre>
 *   -DAuthHandlers=io.deephaven.oidc.entra.EntraOidcAuthenticationHandler
 *   -Dauthentication.oidc.entra.issuer-uri=https://login.microsoftonline.com/<tenant>/v2.0
 * </pre>
 *
 * @see <a href="https://github.com/crazymatthsu/deephaven-oidc/blob/main/docs/oidc/custom-entra-oidc-handler-with-msal.md">Design notes</a>
 */
public final class EntraOidcAuthenticationHandler implements AuthenticationRequestHandler {

    private static final String PROP_ISSUER = "authentication.oidc.entra.issuer-uri";
    private static final String PROP_AUDIENCE = "authentication.oidc.entra.audience";

    private JwtDecoder jwtDecoder;

    @Override
    public void initialize(String targetUrl) {
        String issuerUri = required(PROP_ISSUER);
        String audience = optional(PROP_AUDIENCE);

        NimbusJwtDecoder decoder = NimbusJwtDecoder.withIssuerLocation(issuerUri).build();

        OAuth2TokenValidator<Jwt> defaults = JwtValidators.createDefaultWithIssuer(issuerUri);
        if (audience != null && !audience.isBlank()) {
            OAuth2TokenValidator<Jwt> audienceValidator = new AudienceValidator(audience);
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(defaults, audienceValidator));
        } else {
            decoder.setJwtValidator(defaults);
        }

        this.jwtDecoder = decoder;
    }

    @Override
    public String getAuthType() {
        return getClass().getName();
    }

    @Override
    public Optional<AuthContext> login(long protocolVersion, ByteBuffer payload,
            HandshakeResponseListener listener) throws AuthenticationException {
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

        String raw = stripBearerPrefix(token.trim());
        try {
            Jwt jwt = jwtDecoder.decode(raw);

            // Roles / groups are available for future entitlement mapping:
            //   jwt.getClaimAsStringList("roles")
            //   jwt.getClaimAsStringList("groups")
            //   jwt.getClaimAsString("oid")
            // For the initial version any cryptographically valid Entra token is admitted.
            return Optional.of(new AuthContext.SuperUser());
        } catch (JwtException e) {
            // Invalid signature, expired, wrong issuer/audience, malformed token, etc.
            return Optional.empty();
        }
    }

    private static String stripBearerPrefix(String token) {
        if (token.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return token.substring(7).trim();
        }
        return token;
    }

    private static String required(String property) {
        String value = optional(property);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Missing required configuration '" + property + "'. "
                            + "Set it as a system property (-D" + property + "=...) or "
                            + "environment variable " + toEnvName(property) + ".");
        }
        return value;
    }

    private static String optional(String property) {
        String fromProp = System.getProperty(property);
        if (fromProp != null && !fromProp.isBlank()) {
            return fromProp.trim();
        }
        String fromEnv = System.getenv(toEnvName(property));
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.trim();
        }
        return null;
    }

    private static String toEnvName(String property) {
        return property.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
    }

    /** Accepts a token when {@code aud} contains the expected audience value. */
    private static final class AudienceValidator implements OAuth2TokenValidator<Jwt> {
        private final String expected;

        AudienceValidator(String expected) {
            this.expected = expected;
        }

        @Override
        public OAuth2TokenValidatorResult validate(Jwt jwt) {
            Object audClaim = jwt.getAudience();
            if (audClaim instanceof Collection<?> auds) {
                for (Object a : auds) {
                    if (expected.equals(String.valueOf(a))) {
                        return OAuth2TokenValidatorResult.success();
                    }
                }
            }
            // Some Entra tokens put the app ID in azp instead of aud
            String azp = jwt.getClaimAsString("azp");
            if (expected.equals(azp)) {
                return OAuth2TokenValidatorResult.success();
            }
            OAuth2Error error = new OAuth2Error(
                    "invalid_token",
                    "Required audience '" + expected + "' not present in token", null);
            return OAuth2TokenValidatorResult.failure(error);
        }
    }
}
