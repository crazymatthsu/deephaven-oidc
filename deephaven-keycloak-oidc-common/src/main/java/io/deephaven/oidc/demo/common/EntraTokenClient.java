package io.deephaven.oidc.demo.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.aad.msal4j.ClientCredentialFactory;
import com.microsoft.aad.msal4j.ClientCredentialParameters;
import com.microsoft.aad.msal4j.ConfidentialClientApplication;
import com.microsoft.aad.msal4j.IAuthenticationResult;
import com.microsoft.aad.msal4j.PublicClientApplication;
import com.microsoft.aad.msal4j.UserNamePasswordParameters;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * MSAL4J-based token client for Microsoft Entra ID.
 *
 * <ul>
 *   <li>{@link #clientCredentialsGrant} — confidential client / service account (OrderSimulator)</li>
 *   <li>{@link #usernamePasswordGrant} — ROPC for demo users (OrderSubscriber). Requires the tenant to
 *       allow Resource Owner Password Credentials for the public app registration; prefer device-code or
 *       interactive flows in production.</li>
 * </ul>
 *
 * <p>Configuration is read from {@link AppConfig} ({@code ENTRA_TENANT_ID}, {@code ENTRA_CLIENT_ID},
 * {@code ENTRA_SCOPE}). The client secret for the confidential flow comes from
 * {@code ENTRA_CLIENT_SECRET}.
 */
public final class EntraTokenClient {

    /** Issued access token plus expiry for proactive refresh. */
    public record Token(String accessToken, Instant expiresAt) {
        public boolean expiresWithin(Duration duration) {
            return Instant.now().plus(duration).isAfter(expiresAt);
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AppConfig config;

    public EntraTokenClient(AppConfig config) {
        this.config = config;
        config.requireEntraConfig();
    }

    /**
     * Client-credentials grant for a confidential app registration (daemon / service account).
     *
     * @param clientSecret value of {@code ENTRA_CLIENT_SECRET} (or an explicit secret)
     */
    public Token clientCredentialsGrant(String clientSecret) {
        if (clientSecret == null || clientSecret.isBlank()) {
            throw new IllegalArgumentException("ENTRA_CLIENT_SECRET is required for client-credentials grant");
        }
        try {
            ConfidentialClientApplication app = ConfidentialClientApplication.builder(
                            config.entraClientId(),
                            ClientCredentialFactory.createFromSecret(clientSecret))
                    .authority(config.entraAuthority())
                    .build();

            Set<String> scopes = Set.of(config.entraScope());
            ClientCredentialParameters params = ClientCredentialParameters.builder(scopes).build();
            IAuthenticationResult result = app.acquireToken(params).join();
            return toToken(result);
        } catch (CompletionException e) {
            throw wrap("client-credentials", e);
        } catch (Exception e) {
            throw new IllegalStateException("Entra client-credentials grant failed: " + e.getMessage(), e);
        }
    }

    /**
     * Resource Owner Password Credentials grant for interactive demo users.
     *
     * <p>Entra must allow ROPC for this public client (legacy; disabled by default on many tenants).
     * For production prefer device-code or authorization-code + PKCE.
     */
    public Token usernamePasswordGrant(String username, String password) {
        try {
            PublicClientApplication app = PublicClientApplication.builder(config.entraClientId())
                    .authority(config.entraAuthority())
                    .build();

            Set<String> scopes = Set.of(config.entraScope());
            UserNamePasswordParameters params = UserNamePasswordParameters.builder(scopes, username, password.toCharArray())
                    .build();
            IAuthenticationResult result = app.acquireToken(params).join();
            return toToken(result);
        } catch (CompletionException e) {
            throw wrap("username-password", e);
        } catch (Exception e) {
            throw new IllegalStateException("Entra username-password grant failed: " + e.getMessage(), e);
        }
    }

    private static Token toToken(IAuthenticationResult result) {
        if (result == null || result.accessToken() == null) {
            throw new IllegalStateException("Entra token response contained no access_token");
        }
        Instant expires = result.expiresOnDate() != null
                ? result.expiresOnDate().toInstant()
                : Instant.now().plusSeconds(3600);
        return new Token(result.accessToken(), expires);
    }

    private static IllegalStateException wrap(String grant, CompletionException e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        return new IllegalStateException(
                "Entra " + grant + " grant failed: " + cause.getMessage(), cause);
    }

    /**
     * Extracts role-like claims from an Entra access token for view selection.
     * Checks {@code roles} (app roles), then {@code groups}, then Keycloak-style
     * {@code realm_access.roles} for tokens issued via a broker.
     */
    public static List<String> rolesFromToken(String accessToken) {
        String[] parts = accessToken.split("\\.");
        if (parts.length < 2) {
            return List.of();
        }
        try {
            byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
            JsonNode root = MAPPER.readTree(payload);

            List<String> roles = stringArrayClaim(root, "roles");
            if (!roles.isEmpty()) {
                return roles;
            }
            List<String> groups = stringArrayClaim(root, "groups");
            if (!groups.isEmpty()) {
                return groups;
            }
            JsonNode realmRoles = root.path("realm_access").path("roles");
            if (realmRoles.isArray()) {
                return StreamSupport.stream(realmRoles.spliterator(), false)
                        .map(JsonNode::asText)
                        .collect(Collectors.toList());
            }
            return List.of();
        } catch (Exception e) {
            return List.of();
        }
    }

    private static List<String> stringArrayClaim(JsonNode root, String name) {
        JsonNode node = root.path(name);
        if (!node.isArray()) {
            return List.of();
        }
        return StreamSupport.stream(node.spliterator(), false)
                .map(JsonNode::asText)
                .collect(Collectors.toList());
    }
}
