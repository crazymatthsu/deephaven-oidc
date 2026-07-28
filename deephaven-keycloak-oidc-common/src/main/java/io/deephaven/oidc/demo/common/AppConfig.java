package io.deephaven.oidc.demo.common;

import java.util.Locale;

/**
 * Endpoint configuration shared by the simulator and subscriber.
 *
 * <p>Every value can be overridden by an environment variable or a system property (system property wins).
 *
 * <p>Set {@code AUTH_PROVIDER=entra} (or system property {@code auth.provider=entra}) to acquire tokens from
 * Microsoft Entra ID via MSAL and present them to
 * {@code io.deephaven.oidc.entra.EntraOidcAuthenticationHandler}. The default remains {@code keycloak} for the
 * local compose stack.
 */
public final class AppConfig {

    public static final String KEYCLOAK_AUTH_TYPE =
            "io.deephaven.authentication.oidc.OidcAuthenticationHandler";
    public static final String ENTRA_AUTH_TYPE =
            "io.deephaven.oidc.entra.EntraOidcAuthenticationHandler";

    /** @deprecated use {@link #KEYCLOAK_AUTH_TYPE} */
    @Deprecated
    public static final String OIDC_AUTH_TYPE = KEYCLOAK_AUTH_TYPE;

    public enum AuthProvider {
        KEYCLOAK,
        ENTRA
    }

    private final AuthProvider authProvider;
    private final String deephavenHost;
    private final int deephavenPort;
    private final boolean tls;
    private final String keycloakUrl;
    private final String realm;
    private final String clientId;
    private final String applicationId;
    private final String entraTenantId;
    private final String entraClientId;
    private final String entraScope;

    public static AppConfig fromEnv() {
        return new AppConfig(
                parseProvider(get("AUTH_PROVIDER", "keycloak")),
                get("DH_HOST", "localhost"),
                Integer.parseInt(get("DH_PORT", "10000")),
                Boolean.parseBoolean(get("DH_TLS", "false")),
                get("KC_URL", "http://keycloak.local:6060"),
                get("KC_REALM", "deephaven_core"),
                get("KC_CLIENT_ID", "deephaven"),
                get("DH_APP_ID", "orders-app"),
                get("ENTRA_TENANT_ID", ""),
                get("ENTRA_CLIENT_ID", ""),
                get("ENTRA_SCOPE", ""));
    }

    private static AuthProvider parseProvider(String raw) {
        String v = raw == null ? "keycloak" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (v) {
            case "entra", "azure", "azuread", "azure-ad", "msal" -> AuthProvider.ENTRA;
            default -> AuthProvider.KEYCLOAK;
        };
    }

    private static String get(String name, String defaultValue) {
        String fromProps = System.getProperty(name.toLowerCase(Locale.ROOT).replace('_', '.'));
        if (fromProps != null && !fromProps.isBlank()) {
            return fromProps;
        }
        String fromEnv = System.getenv(name);
        return fromEnv == null || fromEnv.isBlank() ? defaultValue : fromEnv;
    }

    public AppConfig(AuthProvider authProvider, String deephavenHost, int deephavenPort, boolean tls,
            String keycloakUrl, String realm, String clientId, String applicationId, String entraTenantId,
            String entraClientId, String entraScope) {
        this.authProvider = authProvider;
        this.deephavenHost = deephavenHost;
        this.deephavenPort = deephavenPort;
        this.tls = tls;
        this.keycloakUrl = stripTrailingSlash(keycloakUrl);
        this.realm = realm;
        this.clientId = clientId;
        this.applicationId = applicationId;
        this.entraTenantId = entraTenantId == null ? "" : entraTenantId.trim();
        this.entraClientId = entraClientId == null ? "" : entraClientId.trim();
        this.entraScope = entraScope == null ? "" : entraScope.trim();
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public AuthProvider authProvider() {
        return authProvider;
    }

    /** Auth handler class name presented to Deephaven in the session handshake. */
    public String deephavenAuthType() {
        return authProvider == AuthProvider.ENTRA ? ENTRA_AUTH_TYPE : KEYCLOAK_AUTH_TYPE;
    }

    public String deephavenHost() {
        return deephavenHost;
    }

    public int deephavenPort() {
        return deephavenPort;
    }

    public boolean tls() {
        return tls;
    }

    public String keycloakUrl() {
        return keycloakUrl;
    }

    public String realm() {
        return realm;
    }

    public String clientId() {
        return clientId;
    }

    public String applicationId() {
        return applicationId;
    }

    public String entraTenantId() {
        return entraTenantId;
    }

    public String entraClientId() {
        return entraClientId;
    }

    /**
     * Scope(s) requested from Entra. For client-credentials use {@code api://<app-id>/.default} or
     * {@code <app-id>/.default}. For interactive/ROPC use the same API scope (not {@code openid} alone).
     */
    public String entraScope() {
        return entraScope;
    }

    public String entraAuthority() {
        if (entraTenantId.isBlank()) {
            throw new IllegalStateException(
                    "ENTRA_TENANT_ID is required when AUTH_PROVIDER=entra");
        }
        return "https://login.microsoftonline.com/" + entraTenantId + "/";
    }

    public void requireEntraConfig() {
        if (entraTenantId.isBlank() || entraClientId.isBlank() || entraScope.isBlank()) {
            throw new IllegalStateException(
                    "When AUTH_PROVIDER=entra, set ENTRA_TENANT_ID, ENTRA_CLIENT_ID, and ENTRA_SCOPE");
        }
    }

    /** The gRPC target URI understood by Deephaven's {@code DeephavenTarget}, e.g. {@code dh+plain://host:port}. */
    public String deephavenTargetUri() {
        return (tls ? "dh://" : "dh+plain://") + deephavenHost + ":" + deephavenPort;
    }

    public String tokenEndpoint() {
        return keycloakUrl + "/realms/" + realm + "/protocol/openid-connect/token";
    }

    @Override
    public String toString() {
        return "AppConfig{authProvider=" + authProvider + ", deephaven=" + deephavenTargetUri()
                + (authProvider == AuthProvider.ENTRA
                        ? ", entraTenant=" + entraTenantId + ", entraClientId=" + entraClientId
                                + ", entraScope=" + entraScope
                        : ", keycloak=" + keycloakUrl + ", realm=" + realm + ", clientId=" + clientId)
                + ", applicationId=" + applicationId + '}';
    }
}
