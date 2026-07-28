# Deephaven server image with DIRECT Microsoft Entra ID authentication (no Keycloak).
#
# Build context is the REPO ROOT (see compose.yaml: build.context: ..) so this Dockerfile can
# reach both the fat jar built by Gradle and the orders demo app shared with the Keycloak stack.
#
# Prerequisite:  ./gradlew :deephaven-entra-oidc-server:fatJar   (scripts/start.sh entra does this)
#
ARG DEEPHAVEN_VERSION=0.39.4

# Stage 1: build the Entra web-login plugin (MSAL.js auth-code + PKCE). Node lives only in this
# stage — the host needs no JS toolchain.
FROM node:20-slim AS js-plugin
WORKDIR /plugin
COPY js-plugin-auth-entra/package.json ./
RUN npm install --no-audit --no-fund
COPY js-plugin-auth-entra/ ./
RUN npm run build

# Stage 2: the server itself.
FROM ghcr.io/deephaven/server:${DEEPHAVEN_VERSION}

# Self-contained Entra OIDC handler + role-based authorization provider (handler + Spring
# Security + Nimbus + EntraServerMain/EntraAuthorizationProvider). Loaded via
# EXTRA_CLASSPATH=/apps/libs/* (set in compose.yaml).
COPY --chmod=644 deephaven-entra-oidc-server/build/libs/deephaven-entra-oidc-auth-all.jar /apps/libs/

# Launch the custom server assembly: identical to the community server except the allow-all
# authorization is replaced by role-based entitlements (EntraAuthorizationProvider). The stock
# start script hardcodes the main class, so swap it.
RUN sed -i 's/io\.deephaven\.server\.jetty\.JettyMain/io.deephaven.oidc.entra.authz.EntraServerMain/' \
    /opt/deephaven/server-jetty/bin/start

# Browser login flow: the Entra/MSAL.js plugin (redirects the web IDE to login.microsoftonline.com;
# enterprise SSO + Microsoft Authenticator MFA per tenant policy). Same js-plugins layout the
# web-plugin-packager produces: <package-name>/dist/index.js + a root manifest.json.
COPY --from=js-plugin /plugin/dist/ /opt/deephaven/config/js-plugins/@deephaven-oidc/js-plugin-auth-entra/dist/
COPY --from=js-plugin /plugin/package.json /opt/deephaven/config/js-plugins/@deephaven-oidc/js-plugin-auth-entra/
COPY deephaven-entra-oidc-server/docker/deephaven/js-plugins-manifest.json /opt/deephaven/config/js-plugins/manifest.json

# Application-mode script: keyed orders input table, entitlements table, per-role views.
# Shared with the Keycloak stack (canonical copy lives in the keycloak module).
COPY deephaven-keycloak-oidc-server/docker/deephaven/app.d/ /app.d/
