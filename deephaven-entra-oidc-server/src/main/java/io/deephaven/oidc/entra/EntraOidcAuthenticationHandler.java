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
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;

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
 *   <li>{@code authentication.oidc.entra.superuser-roles} — comma-separated role claims that map
 *       to {@link AuthContext.SuperUser} (default {@code dh-admin}). All other valid tokens are
 *       admitted as {@link EntraUserAuthContext} carrying the principal's id, username, and
 *       roles.</li>
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
    private static final String PROP_SUPERUSER_ROLES = "authentication.oidc.entra.superuser-roles";
    private static final String DEFAULT_SUPERUSER_ROLES = "dh-admin";

    private static final Logger log = Logger.getLogger(EntraOidcAuthenticationHandler.class.getName());

    private JwtDecoder jwtDecoder;
    private Set<String> superuserRoles = Set.of();

    @Override
    public void initialize(String targetUrl) {
        String issuerUri = required(PROP_ISSUER);
        String audience = optional(PROP_AUDIENCE);

        String superuserRolesRaw = optional(PROP_SUPERUSER_ROLES);
        superuserRoles = Arrays.stream(
                (superuserRolesRaw == null ? DEFAULT_SUPERUSER_ROLES : superuserRolesRaw).split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());

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
            return Optional.of(toAuthContext(jwt));
        } catch (JwtException e) {
            // Invalid signature, expired, wrong issuer/audience, malformed token, etc.
            log.warning(() -> "Entra login rejected: " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Maps a validated token to an {@link AuthContext}: tokens holding one of the configured
     * superuser roles become {@link AuthContext.SuperUser}; everything else becomes a
     * {@link EntraUserAuthContext} carrying identity + roles.
     */
    private AuthContext toAuthContext(Jwt jwt) {
        // Stable object id of the user or service principal; sub is always present as fallback.
        String oid = firstNonBlank(jwt.getClaimAsString("oid"), jwt.getSubject());
        // Human users carry preferred_username/upn; client-credentials tokens identify the app.
        String username = firstNonBlank(
                jwt.getClaimAsString("preferred_username"),
                jwt.getClaimAsString("upn"),
                jwt.getClaimAsString("azp"),
                jwt.getClaimAsString("appid"),
                oid);
        Set<String> roles = extractRoles(jwt);

        boolean superuser = roles.stream().anyMatch(superuserRoles::contains);
        log.info("Entra login: user=" + username + " (oid=" + oid + ") roles=" + roles
                + (superuser ? " -> SuperUser" : " -> user context"));
        return superuser
                ? new AuthContext.SuperUser()
                : new EntraUserAuthContext(oid, username, roles);
    }

    /** {@code roles} (Entra app roles — recommended), else {@code groups}, else empty. */
    private static Set<String> extractRoles(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles != null && !roles.isEmpty()) {
            return new LinkedHashSet<>(roles);
        }
        List<String> groups = jwt.getClaimAsStringList("groups");
        if (groups != null && !groups.isEmpty()) {
            return new LinkedHashSet<>(groups);
        }
        return Set.of();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "unknown";
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
