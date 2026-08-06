# Custom Deephaven server image with Keycloak OIDC authentication and the orders demo app.
ARG DEEPHAVEN_VERSION=41.3

# Stage 1: package the Keycloak login plugin for the web IDE.
FROM ghcr.io/deephaven/web-plugin-packager:latest AS js-plugins
RUN ./pack-plugins.sh @deephaven/js-plugin-auth-keycloak

# Stage 2: the server itself.
FROM ghcr.io/deephaven/server:${DEEPHAVEN_VERSION}
ARG DEEPHAVEN_VERSION

# Server-side OIDC authentication handler. The published artifact is a fat jar (bundles pac4j and friends), so this
# single file is the only classpath addition needed. Loaded via EXTRA_CLASSPATH=/apps/libs/* (set in compose.yaml).
ADD --chmod=644 https://repo1.maven.org/maven2/io/deephaven/deephaven-oidc-authentication-provider/${DEEPHAVEN_VERSION}/deephaven-oidc-authentication-provider-${DEEPHAVEN_VERSION}.jar /apps/libs/

# Browser login flow (redirects the web IDE to Keycloak).
COPY --from=js-plugins js-plugins/ /opt/deephaven/config/js-plugins/

# The published auth-keycloak plugin (0.2.0, built 2023) expects the web UI's module shim to expose
# @deephaven/log as a namespace ({ Log }), but current web UIs expose the default export directly,
# so `log$1.Log.module(...)` throws and the plugin is skipped ("No login plugins found"). Patch the
# single offending access to fall back to the default export.
RUN sed -i 's/log\$1\.Log\.module/(log$1.Log || log$1).module/' \
    "/opt/deephaven/config/js-plugins/@deephaven/js-plugin-auth-keycloak/dist/index.js"

# Second compatibility patch: the plugin bundles an old keycloak-js that validates the OIDC nonce on
# the access and refresh tokens too, but Keycloak 25+ only puts the nonce claim in the ID token, so
# every login was rejected as "invalid nonce" (an undefined init error). Match modern keycloak-js
# (24+) and validate the nonce on the ID token only.
RUN sed -i 's/kc\.tokenParsed && kc\.tokenParsed\.nonce != oauth\.storedNonce || kc\.refreshTokenParsed && kc\.refreshTokenParsed\.nonce != oauth\.storedNonce || kc\.idTokenParsed && kc\.idTokenParsed\.nonce/kc.idTokenParsed \&\& kc.idTokenParsed.nonce/' \
    "/opt/deephaven/config/js-plugins/@deephaven/js-plugin-auth-keycloak/dist/index.js"

# Application-mode script: keyed orders input table, entitlements table, per-role views.
COPY app.d/ /app.d/
