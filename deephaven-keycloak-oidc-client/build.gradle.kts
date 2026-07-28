plugins {
    java
}

dependencies {
    implementation(project(":deephaven-keycloak-oidc-common"))
}

// Arrow's off-heap memory and the client-side Deephaven engine need these on JDK 17+.
val demoJvmArgs = listOf(
    "--add-opens=java.base/java.nio=ALL-UNNAMED",
    "--add-opens=java.base/java.lang=ALL-UNNAMED",
    "--add-opens=java.base/java.util=ALL-UNNAMED",
)

tasks.register<JavaExec>("runSimulator") {
    group = "demo"
    description = "Publishes mock order upserts into the keyed orders input table (service-account auth)"
    mainClass = "io.deephaven.oidc.demo.client.OrderSimulator"
    classpath = sourceSets.main.get().runtimeClasspath
    jvmArgs(demoJvmArgs)
}

tasks.register<JavaExec>("runSubscriber") {
    group = "demo"
    description = "Subscribes to the caller's entitled orders view over Barrage. Keycloak/ROPC: -Puser=alice -Ppassword=alice. " +
            "Entra device-code/interactive flows need no credentials (sign in via browser + Authenticator MFA)."
    mainClass = "io.deephaven.oidc.demo.client.OrderSubscriber"
    classpath = sourceSets.main.get().runtimeClasspath
    jvmArgs(demoJvmArgs)
    (findProperty("user") as String?)?.let { args("--user", it) }
    (findProperty("password") as String?)?.let { args("--password", it) }
}
