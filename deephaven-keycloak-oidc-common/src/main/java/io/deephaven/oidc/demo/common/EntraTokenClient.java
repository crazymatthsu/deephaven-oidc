package io.deephaven.oidc.demo.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.aad.msal4j.ClientCredentialFactory;
import com.microsoft.aad.msal4j.ClientCredentialParameters;
import com.microsoft.aad.msal4j.ConfidentialClientApplication;
import com.microsoft.aad.msal4j.DeviceCode;
import com.microsoft.aad.msal4j.DeviceCodeFlowParameters;
import com.microsoft.aad.msal4j.IAccount;
import com.microsoft.aad.msal4j.IAuthenticationResult;
import com.microsoft.aad.msal4j.InteractiveRequestParameters;
import com.microsoft.aad.msal4j.PublicClientApplication;
import com.microsoft.aad.msal4j.SilentParameters;
import com.microsoft.aad.msal4j.UserNamePasswordParameters;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * MSAL4J-based token client for Microsoft Entra ID.
 *
 * <p>User flows (OrderSubscriber; selected via {@code ENTRA_USER_FLOW}, see {@link AppConfig.UserFlow}):
 * <ul>
 *   <li>{@link #deviceCodeGrant} — <b>default.</b> Prints a verification URL + one-time code; the user
 *       completes sign-in in any browser and approves with <b>Microsoft Authenticator</b> (push /
 *       number matching). Works from headless terminals; fully MFA-capable.</li>
 *   <li>{@link #interactiveGrant} — opens the system browser (auth-code + PKCE with a localhost
 *       redirect). Also fully MFA-capable; needs a desktop browser on the same machine.</li>
 *   <li>{@link #usernamePasswordGrant} — legacy ROPC fallback. <b>Cannot satisfy MFA</b> and is
 *       disabled on most tenants; kept only for MFA-exempt test tenants.</li>
 * </ul>
 *
 * <p>Service flow (OrderSimulator): {@link #clientCredentialsGrant} — confidential client daemon;
 * MFA does not apply to service principals.
 *
 * <p>All user flows share one {@link PublicClientApplication} (and its in-memory token cache), so
 * {@link #acquireSilently} can renew tokens without re-prompting while the refresh token is valid.
 *
 * <p>Configuration is read from {@link AppConfig} ({@code ENTRA_TENANT_ID}, {@code ENTRA_CLIENT_ID},
 * {@code ENTRA_SCOPE}). The client secret for the confidential flow comes from
 * {@code ENTRA_CLIENT_SECRET}.
 */
public final class EntraTokenClient {

    /** Issued access token plus expiry for proactive refresh; {@code username} is empty for daemons. */
    public record Token(String accessToken, Instant expiresAt, String username) {
        public boolean expiresWithin(Duration duration) {
            return Instant.now().plus(duration).isAfter(expiresAt);
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AppConfig config;
    private volatile PublicClientApplication publicApp;
    private volatile ConfidentialClientApplication confidentialApp;

    public EntraTokenClient(AppConfig config) {
        this.config = config;
        config.requireEntraConfig();
    }

    /** Lazily-built shared confidential client; one instance = one MSAL app-token cache. */
    private ConfidentialClientApplication confidentialApp(String clientSecret) {
        ConfidentialClientApplication app = confidentialApp;
        if (app == null) {
            synchronized (this) {
                if (confidentialApp == null) {
                    if (clientSecret == null || clientSecret.isBlank()) {
                        throw new IllegalArgumentException(
                                "ENTRA_CLIENT_SECRET is required for client-credentials grant");
                    }
                    try {
                        confidentialApp = ConfidentialClientApplication.builder(
                                        config.entraClientId(),
                                        ClientCredentialFactory.createFromSecret(clientSecret))
                                .authority(config.entraAuthority())
                                .build();
                    } catch (Exception e) {
                        throw new IllegalStateException(
                                "Failed to build MSAL confidential client: " + e.getMessage(), e);
                    }
                }
                app = confidentialApp;
            }
        }
        return app;
    }

    /** Lazily-built shared public client; one instance = one MSAL token cache for silent renewal. */
    private PublicClientApplication publicApp() {
        PublicClientApplication app = publicApp;
        if (app == null) {
            synchronized (this) {
                if (publicApp == null) {
                    try {
                        publicApp = PublicClientApplication.builder(config.entraClientId())
                                .authority(config.entraAuthority())
                                .build();
                    } catch (Exception e) {
                        throw new IllegalStateException("Failed to build MSAL public client: " + e.getMessage(), e);
                    }
                }
                app = publicApp;
            }
        }
        return app;
    }

    /**
     * Client-credentials grant for a confidential app registration (daemon / service account).
     * Served from the shared MSAL app-token cache when a valid token exists.
     *
     * @param clientSecret value of {@code ENTRA_CLIENT_SECRET} (or an explicit secret)
     */
    public Token clientCredentialsGrant(String clientSecret) {
        return clientCredentialsGrant(clientSecret, false);
    }

    private Token clientCredentialsGrant(String clientSecret, boolean forceRefresh) {
        try {
            Set<String> scopes = Set.of(config.entraScope());
            ClientCredentialParameters params = ClientCredentialParameters.builder(scopes)
                    .skipCache(forceRefresh)
                    .build();
            IAuthenticationResult result = confidentialApp(clientSecret).acquireToken(params).join();
            return toToken(result);
        } catch (CompletionException e) {
            throw wrap("client-credentials", e);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Entra client-credentials grant failed: " + e.getMessage(), e);
        }
    }

    /**
     * Refresher for long-running daemons: invoked by {@link RefreshingToken} only near expiry, so
     * it bypasses the MSAL cache to guarantee a genuinely fresh token.
     */
    public RefreshingToken daemonToken(String clientSecret) {
        return RefreshingToken.of(() -> snapshot(clientCredentialsGrant(clientSecret, true)));
    }

    /**
     * Refresher for interactive users (device-code / interactive flows): silent renewal via the
     * shared MSAL cache and refresh token — no new sign-in or MFA prompt while the refresh token
     * is valid — falling back to the configured interactive flow only when silent renewal fails.
     */
    public RefreshingToken userToken() {
        return RefreshingToken.of(() -> snapshot(
                acquireSilently(true).orElseGet(() -> switch (config.entraUserFlow()) {
                    case INTERACTIVE -> interactiveGrant();
                    // DEVICE_CODE and (misconfigured) ROPC both fall back to device code —
                    // ROPC credentials are not retained for re-grants.
                    default -> deviceCodeGrant();
                })));
    }

    private static RefreshingToken.Snapshot snapshot(Token token) {
        return new RefreshingToken.Snapshot(token.accessToken(), token.expiresAt());
    }

    /**
     * Device-code flow — the default MFA-capable user flow. Prints a verification URL and one-time
     * code; the user opens the URL in any browser, enters the code, signs in, and approves the
     * Microsoft Authenticator prompt. This process then receives the tokens.
     *
     * <p>Requires "Allow public client flows" to be enabled on the app registration.
     */
    public Token deviceCodeGrant() {
        return deviceCodeGrant(deviceCode -> {
            System.out.println();
            System.out.println("=== Microsoft Entra ID sign-in required ===");
            // Standard message: "To sign in, use a web browser to open https://microsoft.com/devicelogin
            // and enter the code XXXXXXX to authenticate."
            System.out.println(deviceCode.message());
            System.out.println("Approve the Microsoft Authenticator prompt (MFA) to finish.");
            System.out.println();
        });
    }

    /** Device-code flow with a custom presentation of the verification URL + user code. */
    public Token deviceCodeGrant(Consumer<DeviceCode> deviceCodePrompt) {
        try {
            Set<String> scopes = Set.of(config.entraScope());
            DeviceCodeFlowParameters params = DeviceCodeFlowParameters.builder(scopes, deviceCodePrompt)
                    .build();
            return toToken(publicApp().acquireToken(params).join());
        } catch (CompletionException e) {
            throw wrap("device-code", e);
        } catch (Exception e) {
            throw new IllegalStateException("Entra device-code grant failed: " + e.getMessage(), e);
        }
    }

    /**
     * Interactive flow — opens the system default browser (authorization-code + PKCE, localhost
     * redirect). Fully MFA-capable; requires a desktop browser on this machine.
     *
     * <p>The app registration must list {@code http://localhost} as a "Mobile and desktop
     * applications" redirect URI.
     */
    public Token interactiveGrant() {
        try {
            Set<String> scopes = Set.of(config.entraScope());
            InteractiveRequestParameters params = InteractiveRequestParameters
                    .builder(URI.create("http://localhost"))
                    .scopes(scopes)
                    .build();
            System.out.println("Opening the system browser for Entra ID sign-in "
                    + "(approve the Microsoft Authenticator prompt to finish)...");
            return toToken(publicApp().acquireToken(params).join());
        } catch (CompletionException e) {
            throw wrap("interactive", e);
        } catch (Exception e) {
            throw new IllegalStateException("Entra interactive grant failed: " + e.getMessage(), e);
        }
    }

    /**
     * Resource Owner Password Credentials grant — legacy fallback only. ROPC sends the password
     * straight to the token endpoint, so it <b>cannot complete Microsoft Authenticator MFA</b> and
     * fails on MFA-enforced tenants (AADSTS50076). Use {@link #deviceCodeGrant} instead.
     */
    public Token usernamePasswordGrant(String username, String password) {
        try {
            Set<String> scopes = Set.of(config.entraScope());
            UserNamePasswordParameters params = UserNamePasswordParameters.builder(scopes, username, password.toCharArray())
                    .build();
            return toToken(publicApp().acquireToken(params).join());
        } catch (CompletionException e) {
            throw wrap("username-password", e);
        } catch (Exception e) {
            throw new IllegalStateException("Entra username-password grant failed: " + e.getMessage(), e);
        }
    }

    /**
     * Renews a token from the shared MSAL cache (refresh token) without prompting the user again.
     * Empty when no account is cached yet or silent renewal fails — fall back to an interactive grant.
     */
    public Optional<Token> acquireSilently() {
        return acquireSilently(false);
    }

    /**
     * Silent renewal; with {@code forceRefresh} the cached access token is bypassed and the
     * refresh token is redeemed for a new one (used by {@link #userToken()} near expiry).
     */
    public Optional<Token> acquireSilently(boolean forceRefresh) {
        try {
            PublicClientApplication app = publicApp();
            Set<IAccount> accounts = app.getAccounts().join();
            if (accounts.isEmpty()) {
                return Optional.empty();
            }
            SilentParameters params = SilentParameters
                    .builder(Set.of(config.entraScope()), accounts.iterator().next())
                    .forceRefresh(forceRefresh)
                    .build();
            return Optional.of(toToken(app.acquireTokenSilently(params).join()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static Token toToken(IAuthenticationResult result) {
        if (result == null || result.accessToken() == null) {
            throw new IllegalStateException("Entra token response contained no access_token");
        }
        Instant expires = result.expiresOnDate() != null
                ? result.expiresOnDate().toInstant()
                : Instant.now().plusSeconds(3600);
        String username = result.account() != null && result.account().username() != null
                ? result.account().username()
                : "";
        return new Token(result.accessToken(), expires, username);
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
