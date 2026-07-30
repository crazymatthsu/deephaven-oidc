package io.deephaven.oidc.demo.common;

import io.deephaven.oidc.demo.common.AppConfig.AuthProvider;
import io.deephaven.oidc.demo.common.AppConfig.UserFlow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link AppConfig#fromEnv()} reads system properties (lowercase, dotted) before environment
 * variables, so tests drive it via properties and clear them afterwards.
 */
class AppConfigTest {

    private static final String[] PROPS = {
            "auth.provider", "entra.user.flow", "entra.tenant.id", "entra.client.id",
            "entra.scope", "dh.tls", "dh.host", "dh.port", "kc.url", "kc.realm",
    };

    @AfterEach
    void clearProps() {
        for (String p : PROPS) {
            System.clearProperty(p);
        }
    }

    @Test
    void defaultsToKeycloak() {
        assumeTrue(System.getenv("AUTH_PROVIDER") == null, "AUTH_PROVIDER set in this shell");
        AppConfig config = AppConfig.fromEnv();
        assertEquals(AuthProvider.KEYCLOAK, config.authProvider());
        assertEquals(AppConfig.KEYCLOAK_AUTH_TYPE, config.deephavenAuthType());
    }

    @Test
    void providerAliasesParse() {
        for (String alias : new String[] {"entra", "ENTRA", "azure", "azuread", "azure-ad", "msal"}) {
            System.setProperty("auth.provider", alias);
            assertEquals(AuthProvider.ENTRA, AppConfig.fromEnv().authProvider(), alias);
        }
        System.setProperty("auth.provider", "anything-else");
        assertEquals(AuthProvider.KEYCLOAK, AppConfig.fromEnv().authProvider());
    }

    @Test
    void entraSelectsEntraAuthType() {
        System.setProperty("auth.provider", "entra");
        assertEquals(AppConfig.ENTRA_AUTH_TYPE, AppConfig.fromEnv().deephavenAuthType());
    }

    @Test
    void userFlowParsesAliasesAndDefaults() {
        assumeTrue(System.getenv("ENTRA_USER_FLOW") == null, "ENTRA_USER_FLOW set in this shell");
        assertEquals(UserFlow.DEVICE_CODE, AppConfig.fromEnv().entraUserFlow());

        for (String alias : new String[] {"interactive", "browser", "authcode", "auth-code"}) {
            System.setProperty("entra.user.flow", alias);
            assertEquals(UserFlow.INTERACTIVE, AppConfig.fromEnv().entraUserFlow(), alias);
        }
        for (String alias : new String[] {"ropc", "password", "username_password"}) {
            System.setProperty("entra.user.flow", alias);
            assertEquals(UserFlow.ROPC, AppConfig.fromEnv().entraUserFlow(), alias);
        }
        System.setProperty("entra.user.flow", "device-code");
        assertEquals(UserFlow.DEVICE_CODE, AppConfig.fromEnv().entraUserFlow());
    }

    @Test
    void targetUriReflectsTls() {
        assumeTrue(System.getenv("DH_TLS") == null && System.getenv("DH_HOST") == null,
                "DH_* set in this shell");
        assertEquals("dh+plain://localhost:10000", AppConfig.fromEnv().deephavenTargetUri());
        System.setProperty("dh.tls", "true");
        System.setProperty("dh.host", "dh.example.com");
        System.setProperty("dh.port", "443");
        assertEquals("dh://dh.example.com:443", AppConfig.fromEnv().deephavenTargetUri());
    }

    @Test
    void keycloakTokenEndpointFromUrlAndRealm() {
        System.setProperty("kc.url", "https://auth.example.com/");
        System.setProperty("kc.realm", "prod");
        // trailing slash on the base URL is normalized away
        assertEquals("https://auth.example.com/realms/prod/protocol/openid-connect/token",
                AppConfig.fromEnv().tokenEndpoint());
    }

    @Test
    void entraAuthorityRequiresTenant() {
        assumeTrue(System.getenv("ENTRA_TENANT_ID") == null, "ENTRA_TENANT_ID set in this shell");
        System.setProperty("auth.provider", "entra");
        assertThrows(IllegalStateException.class, () -> AppConfig.fromEnv().entraAuthority());

        System.setProperty("entra.tenant.id", "my-tenant");
        assertEquals("https://login.microsoftonline.com/my-tenant/",
                AppConfig.fromEnv().entraAuthority());
    }

    @Test
    void requireEntraConfigNeedsAllThree() {
        assumeTrue(System.getenv("ENTRA_TENANT_ID") == null && System.getenv("ENTRA_CLIENT_ID") == null
                && System.getenv("ENTRA_SCOPE") == null, "ENTRA_* set in this shell");
        System.setProperty("auth.provider", "entra");
        System.setProperty("entra.tenant.id", "t");
        System.setProperty("entra.client.id", "c");
        assertThrows(IllegalStateException.class, () -> AppConfig.fromEnv().requireEntraConfig());

        System.setProperty("entra.scope", "api://x/.default");
        AppConfig.fromEnv().requireEntraConfig(); // no throw
    }
}
