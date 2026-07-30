package io.deephaven.oidc.demo.common;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Claim extraction from raw JWTs ({@link EntraTokenClient#rolesFromToken} and
 * {@link KeycloakTokenClient#realmRoles}). Tokens are hand-built (unsigned) — these helpers only
 * decode the payload; signature verification is the server's job.
 */
class RoleExtractionTest {

    private static String jwt(String payloadJson) {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String header = b64.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String payload = b64.encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".sig";
    }

    @Test
    void entraAppRolesPreferred() {
        String token = jwt("{\"roles\":[\"trader-us\",\"writer\"],\"groups\":[\"g-guid\"]}");
        assertEquals(List.of("trader-us", "writer"), EntraTokenClient.rolesFromToken(token));
    }

    @Test
    void entraGroupsFallbackWhenNoRoles() {
        String token = jwt("{\"groups\":[\"g1\",\"g2\"]}");
        assertEquals(List.of("g1", "g2"), EntraTokenClient.rolesFromToken(token));
    }

    @Test
    void entraKeycloakStyleRealmRolesLastResort() {
        // Broker scenario: Keycloak-issued token inspected by the Entra-side helper.
        String token = jwt("{\"realm_access\":{\"roles\":[\"dh-admin\"]}}");
        assertEquals(List.of("dh-admin"), EntraTokenClient.rolesFromToken(token));
    }

    @Test
    void entraEmptyAndMalformedYieldNoRoles() {
        assertTrue(EntraTokenClient.rolesFromToken(jwt("{}")).isEmpty());
        assertTrue(EntraTokenClient.rolesFromToken("not-a-jwt").isEmpty());
        assertTrue(EntraTokenClient.rolesFromToken(jwt("{\"roles\":\"not-an-array\"}")).isEmpty());
    }

    @Test
    void keycloakRealmRoles() {
        String token = jwt("{\"realm_access\":{\"roles\":[\"trader-emea\",\"offline_access\"]}}");
        assertEquals(List.of("trader-emea", "offline_access"), KeycloakTokenClient.realmRoles(token));
        assertTrue(KeycloakTokenClient.realmRoles(jwt("{}")).isEmpty());
        assertTrue(KeycloakTokenClient.realmRoles("garbage").isEmpty());
    }
}
