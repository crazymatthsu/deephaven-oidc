plugins {
    // Auto-provisions a JDK 17 toolchain if only newer/older JDKs are installed locally.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

rootProject.name = "deephaven-oidc"

include("deephaven-keycloak-oidc-common")
include("deephaven-keycloak-oidc-client")
include("deephaven-keycloak-oidc-server")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
