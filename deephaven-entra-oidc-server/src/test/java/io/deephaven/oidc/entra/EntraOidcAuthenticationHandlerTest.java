package io.deephaven.oidc.entra;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import io.deephaven.auth.AuthContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the handler end-to-end against an embedded mock issuer: OIDC discovery + JWKS served
 * from a local {@link HttpServer}, tokens minted with the matching RSA key. No Entra tenant or
 * network access required.
 */
class EntraOidcAuthenticationHandlerTest {

    private static final String AUDIENCE = "api://deephaven-test";

    private static HttpServer server;
    private static String issuer;
    private static RSAKey rsaKey;
    private static EntraOidcAuthenticationHandler handler;

    @BeforeAll
    static void startMockIssuerAndInitialize() throws Exception {
        rsaKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
        String jwks = new JWKSet(rsaKey.toPublicJWK()).toString();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        issuer = "http://127.0.0.1:" + server.getAddress().getPort() + "/mock";
        String discovery = "{"
                + "\"issuer\":\"" + issuer + "\","
                + "\"authorization_endpoint\":\"" + issuer + "/authorize\","
                + "\"token_endpoint\":\"" + issuer + "/token\","
                + "\"jwks_uri\":\"" + issuer + "/jwks\","
                + "\"response_types_supported\":[\"code\"],"
                + "\"subject_types_supported\":[\"public\"],"
                + "\"id_token_signing_alg_values_supported\":[\"RS256\"]"
                + "}";
        serveJson("/mock/.well-known/openid-configuration", discovery);
        serveJson("/mock/jwks", jwks);
        server.start();

        System.setProperty("authentication.oidc.entra.issuer-uri", issuer);
        System.setProperty("authentication.oidc.entra.audience", AUDIENCE);
        System.setProperty("authentication.oidc.entra.superuser-roles", "dh-admin");

        handler = new EntraOidcAuthenticationHandler();
        handler.initialize("test");
    }

    @AfterAll
    static void stop() {
        server.stop(0);
        System.clearProperty("authentication.oidc.entra.issuer-uri");
        System.clearProperty("authentication.oidc.entra.audience");
        System.clearProperty("authentication.oidc.entra.superuser-roles");
    }

    private static void serveJson(String path, String body) {
        server.createContext(path, exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }

    private static String mint(JWTClaimsSet.Builder claims) throws Exception {
        JWTClaimsSet claimsSet = claims
                .issuer(issuer)
                .issueTime(new Date())
                .expirationTime(new Date(System.currentTimeMillis() + 300_000))
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaKey.getKeyID()).build(),
                claimsSet);
        jwt.sign(new RSASSASigner(rsaKey));
        return jwt.serialize();
    }

    private static JWTClaimsSet.Builder aliceClaims() {
        return new JWTClaimsSet.Builder()
                .subject("sub-alice")
                .audience(AUDIENCE)
                .claim("oid", "oid-alice")
                .claim("preferred_username", "alice@contoso.com");
    }

    private static Optional<AuthContext> login(String token) throws Exception {
        return handler.login(token, null);
    }

    @Test
    void userTokenMapsToEntraUserContextWithRoles() throws Exception {
        String token = mint(aliceClaims().claim("roles", List.of("trader-us")));
        Optional<AuthContext> context = login(token);
        EntraUserAuthContext user = assertInstanceOf(EntraUserAuthContext.class, context.orElseThrow());
        assertEquals("oid-alice", user.userId());
        assertEquals("alice@contoso.com", user.username());
        assertEquals(Set.of("trader-us"), user.roles());
    }

    @Test
    void superuserRoleMapsToSuperUser() throws Exception {
        String token = mint(aliceClaims().claim("roles", List.of("trader-us", "dh-admin")));
        assertInstanceOf(AuthContext.SuperUser.class, login(token).orElseThrow());
    }

    @Test
    void groupsClaimIsFallbackWhenRolesAbsent() throws Exception {
        String token = mint(aliceClaims().claim("groups", List.of("trader-emea")));
        EntraUserAuthContext user =
                assertInstanceOf(EntraUserAuthContext.class, login(token).orElseThrow());
        assertEquals(Set.of("trader-emea"), user.roles());
    }

    @Test
    void servicePrincipalTokenUsesAzpForUsername() throws Exception {
        // Client-credentials tokens carry no preferred_username; azp identifies the app.
        String token = mint(new JWTClaimsSet.Builder()
                .subject("sub-app")
                .audience(AUDIENCE)
                .claim("oid", "oid-app")
                .claim("azp", "simulator-client-id")
                .claim("roles", List.of("writer")));
        EntraUserAuthContext app =
                assertInstanceOf(EntraUserAuthContext.class, login(token).orElseThrow());
        assertEquals("simulator-client-id", app.username());
        assertEquals(Set.of("writer"), app.roles());
    }

    @Test
    void bearerPrefixIsStripped() throws Exception {
        String token = mint(aliceClaims().claim("roles", List.of("trader-us")));
        assertTrue(login("Bearer " + token).isPresent());
    }

    @Test
    void azpSatisfiesAudienceWhenAudDoesNot() throws Exception {
        String token = mint(new JWTClaimsSet.Builder()
                .subject("sub-alice")
                .audience("someone-else")
                .claim("azp", AUDIENCE));
        assertTrue(login(token).isPresent());
    }

    @Test
    void wrongAudienceIsRejected() throws Exception {
        String token = mint(aliceClaims().audience("api://other-app"));
        assertTrue(login(token).isEmpty());
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        JWTClaimsSet claimsSet = aliceClaims()
                .issuer(issuer)
                .issueTime(new Date(System.currentTimeMillis() - 600_000))
                .expirationTime(new Date(System.currentTimeMillis() - 300_000))
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaKey.getKeyID()).build(), claimsSet);
        jwt.sign(new RSASSASigner(rsaKey));
        assertTrue(login(jwt.serialize()).isEmpty());
    }

    @Test
    void tokenSignedByUnknownKeyIsRejected() throws Exception {
        RSAKey rogue = new RSAKeyGenerator(2048).keyID("rogue").generate();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("rogue").build(),
                aliceClaims().issuer(issuer)
                        .issueTime(new Date())
                        .expirationTime(new Date(System.currentTimeMillis() + 300_000))
                        .build());
        jwt.sign(new RSASSASigner(rogue));
        assertTrue(login(jwt.serialize()).isEmpty());
    }

    @Test
    void garbageAndBlankAreRejected() throws Exception {
        assertTrue(login("not-a-jwt").isEmpty());
        assertTrue(login("   ").isEmpty());
        assertTrue(login(null).isEmpty());
    }
}
