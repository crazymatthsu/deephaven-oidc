package io.deephaven.oidc.demo.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Minimal OAuth2 token client for Keycloak. Supports the resource-owner password grant (interactive demo users) and
 * the client-credentials grant (the order-simulator service account). No third-party OIDC library needed — plain
 * {@link HttpClient} + Jackson.
 */
public final class KeycloakTokenClient {

    /** An issued access token plus its expiry, so long-running clients can refresh proactively. */
    public record Token(String accessToken, Instant expiresAt) {
        public boolean expiresWithin(Duration duration) {
            return Instant.now().plus(duration).isAfter(expiresAt);
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AppConfig config;
    private final HttpClient http;

    public KeycloakTokenClient(AppConfig config) {
        this.config = config;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** Password grant against the public {@code deephaven} client (requires direct access grants enabled). */
    public Token passwordGrant(String username, String password) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "password");
        form.put("client_id", config.clientId());
        form.put("username", username);
        form.put("password", password);
        form.put("scope", "openid email profile");
        return requestToken(form);
    }

    /** Client-credentials grant for a confidential service-account client, e.g. {@code order-simulator}. */
    public Token clientCredentialsGrant(String serviceClientId, String clientSecret) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "client_credentials");
        form.put("client_id", serviceClientId);
        form.put("client_secret", clientSecret);
        form.put("scope", "openid email profile");
        return requestToken(form);
    }

    private Token requestToken(Map<String, String> form) {
        String body = form.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        HttpRequest request = HttpRequest.newBuilder(URI.create(config.tokenEndpoint()))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException(
                        "Keycloak token request failed: HTTP " + response.statusCode() + ": " + response.body());
            }
            JsonNode json = MAPPER.readTree(response.body());
            String accessToken = json.path("access_token").asText(null);
            if (accessToken == null) {
                throw new IllegalStateException("Keycloak response contained no access_token: " + response.body());
            }
            long expiresIn = json.path("expires_in").asLong(60);
            return new Token(accessToken, Instant.now().plusSeconds(expiresIn));
        } catch (IOException e) {
            throw new IllegalStateException("Unable to reach Keycloak at " + config.tokenEndpoint()
                    + " — is the stack up, and does keycloak.local resolve? (" + e.getMessage() + ")", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while requesting token", e);
        }
    }

    /**
     * Extracts the realm roles ({@code realm_access.roles}) from a Keycloak access token by decoding the JWT payload.
     * No signature verification — the server does that; this is only used to pick which view to subscribe to.
     */
    public static List<String> realmRoles(String accessToken) {
        String[] parts = accessToken.split("\\.");
        if (parts.length < 2) {
            return List.of();
        }
        try {
            byte[] payload = Base64.getUrlDecoder().decode(parts[1]);
            JsonNode roles = MAPPER.readTree(payload).path("realm_access").path("roles");
            return StreamSupport.stream(roles.spliterator(), false)
                    .map(JsonNode::asText)
                    .collect(Collectors.toList());
        } catch (IOException | IllegalArgumentException e) {
            return List.of();
        }
    }
}
