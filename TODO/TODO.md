## deephaven OIDC with Keycloak
#### goal: 
- implement OIDC authentication with Keycloak for a Deephaven instance.
- and be able to run in podman via docker compose.
- use open source deephaven and Keycloak images.
- assume the user will login via company's single sign-on to windows and access deephaven ui via browser.
- use built in deephaven entitlement system to manage user access.
- how to design entitlement table in deephaven to filter row data baesd on user's roles. 
- create a mock order table with sample data and implement row level security based on user's roles.

- based on the deephaven documentation:
https://deephaven.io/core/docs/how-to-guides/authentication/auth-keycloak/ 
implement OIDC authentication with Keycloak for a Deephaven instance.
run deephaven, Keycloak based on the documentation, and run in podman via docker compose 

#### create a gradle based project with the following submodules:

- deephaven-keycloak-oidc-server 
  - this module will contain the code to run a Keycloak server with OIDC configuration for Deephaven.
- deephaven-keycloak-oidc-client
- deephaven-keycloak-oidc-common

