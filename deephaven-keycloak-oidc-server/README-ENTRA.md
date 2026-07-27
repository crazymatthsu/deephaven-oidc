# Entra ID OIDC Authentication Handler

This module now builds a Java library jar containing
`io.deephaven.oidc.entra.EntraOidcAuthenticationHandler`.

## Build

```bash
./gradlew :deephaven-keycloak-oidc-server:jar
```

Output: `deephaven-keycloak-oidc-server/build/libs/deephaven-entra-oidc-auth-*.jar`

Also collect the Spring Security runtime dependencies (or use a fat/shadow jar in a follow-up):

```bash
./gradlew :deephaven-keycloak-oidc-server:dependencies --configuration runtimeClasspath
```

## Enable on the Deephaven server

```text
EXTRA_CLASSPATH=/path/to/deephaven-entra-oidc-auth.jar:/path/to/spring-*.jar:...

START_OPTS="
  -DAuthHandlers=io.deephaven.oidc.entra.EntraOidcAuthenticationHandler
  -Dauthentication.oidc.entra.issuer-uri=https://login.microsoftonline.com/<tenant-id>/v2.0
  -Dauthentication.oidc.entra.audience=api://<your-app-id-uri>
  -Dauthentication.client.configuration.list=AuthHandlers,authentication.oidc.entra.issuer-uri,authentication.oidc.entra.audience
"
```

Environment variable equivalents:

- `AUTHENTICATION_OIDC_ENTRA_ISSUER_URI`
- `AUTHENTICATION_OIDC_ENTRA_AUDIENCE`

## Client usage

```java
SessionConfig.builder()
    .authenticationTypeAndValue(
        "io.deephaven.oidc.entra.EntraOidcAuthenticationHandler " + accessToken)
    .build();
```

Acquire `accessToken` with MSAL Java (client credentials or interactive flow).

## Design notes

See [`docs/oidc/custom-entra-oidc-handler-with-msal.md`](../docs/oidc/custom-entra-oidc-handler-with-msal.md).
