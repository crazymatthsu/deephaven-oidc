plugins {
    `java-library`
}

dependencies {
    api(libs.deephaven.client.barrage)
    implementation(libs.jackson.databind)
    runtimeOnly(libs.deephaven.log.to.slf4j)
    runtimeOnly(libs.slf4j.simple)
}
