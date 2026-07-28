# Deephaven server image with DIRECT Microsoft Entra ID authentication (no Keycloak).
#
# Build context is the REPO ROOT (see compose.yaml: build.context: ..) so this Dockerfile can
# reach both the fat jar built by Gradle and the orders demo app shared with the Keycloak stack.
#
# Prerequisite:  ./gradlew :deephaven-entra-oidc-server:fatJar   (scripts/start.sh entra does this)
#
# NOTE: the web IDE has NO login path in this image yet — there is no published Entra/MSAL.js
# login plugin for Deephaven Community. Only gRPC/Barrage clients (OrderSimulator/OrderSubscriber)
# can authenticate. See docs/oidc/ENTRA-IMPLEMENTATION-PLAN.md Phase 1 for the browser plugin plan.
ARG DEEPHAVEN_VERSION=0.39.4
FROM ghcr.io/deephaven/server:${DEEPHAVEN_VERSION}

# Self-contained Entra OIDC handler (handler + Spring Security + Nimbus). Loaded via
# EXTRA_CLASSPATH=/apps/libs/* (set in compose.yaml).
COPY --chmod=644 deephaven-entra-oidc-server/build/libs/deephaven-entra-oidc-auth-all.jar /apps/libs/

# Application-mode script: keyed orders input table, entitlements table, per-role views.
# Shared with the Keycloak stack (canonical copy lives in the keycloak module).
COPY deephaven-keycloak-oidc-server/docker/deephaven/app.d/ /app.d/
