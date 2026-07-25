package io.deephaven.oidc.demo.common;

import java.util.Locale;

/**
 * Endpoint configuration shared by the simulator and subscriber.
 *
 * <p>Every value can be overridden by an environment variable or a system property (system property wins). The
 * defaults target the local podman-compose stack; for the EKS deployment set {@code DH_HOST}, {@code DH_PORT},
 * {@code DH_TLS=true} and {@code KC_URL} to the public hostnames.
 */
public final class AppConfig {

    public static final String OIDC_AUTH_TYPE = "io.deephaven.authentication.oidc.OidcAuthenticationHandler";

    private final String deephavenHost;
    private final int deephavenPort;
    private final boolean tls;
    private final String keycloakUrl;
    private final String realm;
    private final String clientId;
    private final String applicationId;

    public static AppConfig fromEnv() {
        return new AppConfig(
                get("DH_HOST", "localhost"),
                Integer.parseInt(get("DH_PORT", "10000")),
                Boolean.parseBoolean(get("DH_TLS", "false")),
                get("KC_URL", "http://keycloak.local:6060"),
                get("KC_REALM", "deephaven_core"),
                get("KC_CLIENT_ID", "deephaven"),
                get("DH_APP_ID", "orders-app"));
    }

    private static String get(String name, String defaultValue) {
        String fromProps = System.getProperty(name.toLowerCase(Locale.ROOT).replace('_', '.'));
        if (fromProps != null && !fromProps.isBlank()) {
            return fromProps;
        }
        String fromEnv = System.getenv(name);
        return fromEnv == null || fromEnv.isBlank() ? defaultValue : fromEnv;
    }

    public AppConfig(String deephavenHost, int deephavenPort, boolean tls, String keycloakUrl, String realm,
            String clientId, String applicationId) {
        this.deephavenHost = deephavenHost;
        this.deephavenPort = deephavenPort;
        this.tls = tls;
        this.keycloakUrl = stripTrailingSlash(keycloakUrl);
        this.realm = realm;
        this.clientId = clientId;
        this.applicationId = applicationId;
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
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

    /** The gRPC target URI understood by Deephaven's {@code DeephavenTarget}, e.g. {@code dh+plain://host:port}. */
    public String deephavenTargetUri() {
        return (tls ? "dh://" : "dh+plain://") + deephavenHost + ":" + deephavenPort;
    }

    public String tokenEndpoint() {
        return keycloakUrl + "/realms/" + realm + "/protocol/openid-connect/token";
    }

    @Override
    public String toString() {
        return "AppConfig{deephaven=" + deephavenTargetUri() + ", keycloak=" + keycloakUrl + ", realm=" + realm
                + ", clientId=" + clientId + ", applicationId=" + applicationId + '}';
    }
}
