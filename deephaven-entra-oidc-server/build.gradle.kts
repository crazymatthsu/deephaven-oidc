// Direct Microsoft Entra ID (Azure AD) authentication for Deephaven Community Core — no Keycloak.
//
//   - Java: io.deephaven.oidc.entra.EntraOidcAuthenticationHandler (Spring Security JwtDecoder
//     validating Entra-issued access tokens against the tenant JWKS)
//   - docker/deephaven/   - Deephaven image with the handler fat jar + orders demo app
//   - compose.yaml        - podman/docker compose stack (Deephaven only; Entra ID is the IdP)
//
// Build the deployable fat jar (handler + Spring Security + Nimbus, single file for EXTRA_CLASSPATH):
//   ./gradlew :deephaven-entra-oidc-server:fatJar
// Output: build/libs/deephaven-entra-oidc-auth-all.jar
//
// Bring the stack up with (requires ENTRA_TENANT_ID, ENTRA_AUDIENCE in the environment):
//   scripts/start.sh entra

plugins {
    `java-library`
}

dependencies {
    // Deephaven authentication SPI (AuthenticationRequestHandler, AuthContext) — provided by the server.
    compileOnly(libs.deephaven.authentication)
    // LogOutput/LogOutputAppendable, needed to subclass AuthContext — also provided by the server.
    compileOnly(libs.deephaven.base)

    // Role-based authorization: custom server assembly (ComponentFactoryBase/JettyServerModule),
    // AuthWiring interfaces, engine Table for entitlement attributes. All provided by the server
    // image at runtime — compileOnly keeps them out of the fat jar.
    compileOnly(libs.deephaven.server.jetty)
    compileOnly(libs.deephaven.extensions.flight.sql)
    compileOnly(libs.deephaven.client.barrage) // BarrageSessionFactoryConfig.userAgent
    compileOnly(libs.dagger)
    // Dagger codegen runs at build time to generate our component (DaggerEntraServerComponent...).
    annotationProcessor(libs.dagger.compiler)

    // Spring Security — concise JWT validation against Entra ID JWKS.
    implementation(libs.spring.security.oauth2.resource.server)
    implementation(libs.spring.security.oauth2.jose)

    // Tests: mint RS256 tokens with Nimbus (transitive of oauth2-jose) against an embedded mock
    // issuer; the auth SPI is needed at test runtime since it's compileOnly above.
    testImplementation(libs.deephaven.authentication)
    testImplementation(libs.deephaven.base)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveBaseName.set("deephaven-entra-oidc-auth")
}

// Self-contained jar for the Deephaven server EXTRA_CLASSPATH: the handler plus its full runtime
// closure (Spring Security oauth2 jose/resource-server, Nimbus JOSE+JWT, ...). Mirrors how the
// published deephaven-oidc-authentication-provider ships as a single fat jar.
//
// Deliberately NOT bundled: an slf4j binding (the Deephaven server provides its own logging
// backend; shipping another binding would conflict) and the Deephaven auth SPI (compileOnly —
// present on the server already).
tasks.register<Jar>("fatJar") {
    group = "build"
    description = "Builds the self-contained Entra OIDC handler jar for the Deephaven EXTRA_CLASSPATH"
    archiveBaseName.set("deephaven-entra-oidc-auth")
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    from(sourceSets.main.get().output)
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }) {
        // Signed-jar metadata is invalid once repackaged.
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
        // JPMS descriptors from individual deps don't apply to a merged jar.
        exclude("module-info.class", "META-INF/versions/*/module-info.class")
        // Never bundle a logging backend into the server (see note above).
        exclude("org/slf4j/impl/**")
    }
}
