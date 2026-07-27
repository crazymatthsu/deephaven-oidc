// Server module packages:
//   - Java: custom Entra ID OIDC AuthenticationRequestHandler (Spring JwtDecoder)
//   - docker/deephaven/  - custom Deephaven image (OIDC provider jar + Keycloak JS auth plugin + orders app)
//   - docker/keycloak/   - Keycloak realm import with demo users, roles, and clients
//   - compose.yaml       - podman/docker compose stack wiring the two together
//
// Build the Entra handler JAR:
//   ./gradlew :deephaven-keycloak-oidc-server:jar
// The jar (and its runtime deps) can be placed on the Deephaven EXTRA_CLASSPATH.
//
// Bring the Keycloak stack up with:
//   podman compose -f deephaven-keycloak-oidc-server/compose.yaml up --build

plugins {
    `java-library`
}

dependencies {
    // Deephaven authentication SPI (AuthenticationRequestHandler, AuthContext)
    compileOnly(libs.deephaven.authentication)

    // Spring Security — concise JWT validation against Entra ID JWKS
    implementation(libs.spring.security.oauth2.resource.server)
    implementation(libs.spring.security.oauth2.jose)

    runtimeOnly(libs.slf4j.simple)
}

tasks.jar {
    archiveBaseName.set("deephaven-entra-oidc-auth")
}
