# Secure access to Deephaven on EKS from on-prem clients

This document describes the production shape of the demo in this repo: Deephaven and Keycloak run in
AWS EKS; users log in from the corporate network with their SSO credentials; headless publisher/consumer
processes (the order simulator and Barrage subscribers) run on-prem and connect across the internet.

```
 on-prem                                   AWS
┌───────────────────────────┐   TLS 443  ┌──────────────────────────────────────────┐
│ browser ──────────────────┼───────────►│ ALB (ACM cert) ── deephaven.example.com  │
│   │  SSO login            │            │   │  gRPC/HTTP2 + HTTP1.1                │
│   ▼                       │            │   ▼                                      │
│ OrderSubscriber (Barrage) │            │ Deephaven pod (private subnet)           │
│ OrderSimulator  (DoPut)   │   TLS 443  │   • OIDC handler validates every token   │
│   │  token grants         ├───────────►│   • console disabled                     │
│   ▼                       │            │                                          │
│ corporate credentials ────┼───────────►│ ALB ── auth.deephaven.example.com        │
└───────────────────────────┘            │   ▼                                      │
                                         │ Keycloak pod ── RDS PostgreSQL           │
                                         └──────────────────────────────────────────┘
```

## Authentication flow

1. **Browser (interactive users).** The web IDE at `https://deephaven.example.com/ide` loads the
   `@deephaven/js-plugin-auth-keycloak` plugin (baked into the image), reads the Keycloak URL/realm/client
   from the server's exposed client configuration, and redirects to
   `https://auth.deephaven.example.com` for login. If Keycloak federates the corporate IdP (LDAP/Kerberos/
   SAML/OIDC brokering), users who are already signed in to Windows get true SSO. Keycloak redirects back
   with tokens; the JS plugin hands the access token to the Deephaven handshake.
2. **Headless clients (on-prem).** `OrderSimulator` uses the client-credentials grant with the
   `order-simulator` confidential client; `OrderSubscriber` uses the password grant (or, better, the
   device-authorization grant if you don't want passwords in service configs). Both then open a gRPC
   session presenting `io.deephaven.authentication.oidc.OidcAuthenticationHandler <access-token>`.
3. **Server-side validation.** The OIDC handler (pac4j) validates every presented token against Keycloak
   (issuer, signature via JWKS, expiry) before a session is admitted. Expired token ⇒ handshake rejected.

The same client binaries run against local podman and EKS; only environment changes:
`DH_HOST=deephaven.example.com DH_PORT=443 DH_TLS=true KC_URL=https://auth.deephaven.example.com`.

## Transport security

- **TLS everywhere externally.** Both hostnames are HTTPS-only (ACM certificate on an internet-facing
  ALB, `ssl-redirect` enabled). Deephaven's gRPC traffic (Flight/Barrage) rides HTTP/2 over the same TLS
  listener; the Java client uses `dh://host:443` which enables TLS with the system trust store — no
  custom certs on the client.
- <a name="load-balancer"></a>**Load balancer.** The ALB target group is annotated
  `backend-protocol-version: GRPC` so HTTP/2 streams reach the pod intact. Barrage subscriptions are
  long-lived server-streaming RPCs — the idle timeout is raised to 1h, and clients should reconnect on
  stream reset. If you hit gRPC/HTTP1 mixing issues on older ALB controller versions, the fallback is an
  NLB with TLS passthrough and cert-manager terminating TLS in the pod (Deephaven supports serving TLS
  directly via `ssl.identity.*` properties).
- **Pods in private subnets.** Only the ALB is internet-facing; security groups allow the ALB to reach
  pod port 10000/8080 and nothing else. Keycloak's DB is RDS in a private subnet.

## Authorization / row-level security

Identical to local dev (see repo README): the app-mode script publishes per-role views filtered through
the `entitlements` input table. In EKS the console is disabled (`deephaven.console.disable=true` in
`20-deephaven.yaml`), so no user can execute server-side code to reach the unfiltered `orders` table.
Remember that open-source Deephaven has no per-user object ACLs: anyone with a valid token can fetch any
*published* field. Keep that in mind when deciding what the app script publishes; publish only the
per-role views if the flat `orders` table itself is sensitive (the simulator can address the input table
through a dedicated writer application, or you accept that authenticated users can fetch `orders` and
treat the views as convenience filtering).

## Hardening options (in increasing order of effort)

1. **Source-IP allowlist.** Uncomment `alb.ingress.kubernetes.io/inbound-cidrs` with the on-prem egress
   CIDR — one line to cut the exposure to your corporate network.
2. **mTLS at the edge.** ALB mutual-TLS (verify mode) with a private CA: on-prem clients must present a
   client certificate in addition to their OIDC token.
3. **Private connectivity.** Site-to-site VPN or Direct Connect from the on-prem network to the VPC, with
   an *internal* ALB (`scheme: internal`) — no public endpoint at all. The manifests only change in the
   ingress annotations. AWS PrivateLink is an alternative when the consumer is another AWS account/VPC.
4. **Real enforcement of row-level security.** If script-level filtering is not sufficient, implement a
   custom `AuthorizationProvider`/`TicketResolver` in the server that resolves the caller's roles from the
   auth context and applies filters at the gRPC layer, or move to Deephaven Enterprise, which has a
   built-in entitlement system.

## <a name="secrets"></a>Secrets

`40-secrets.example.yaml` is a placeholder. In production, keep the Keycloak admin credential, the RDS
password, and the `order-simulator` client secret in AWS Secrets Manager, synced by External Secrets
Operator (or mounted via the Secrets Store CSI driver). Rotate the `order-simulator` secret like any
service credential; on-prem processes read it from your on-prem secret store, never from disk in the repo.
Also regenerate the demo realm before any real deployment: every password and secret in
`deephaven_realm.json` is public.

## Keycloak placement

Deploying Keycloak in the same cluster (as in `10-keycloak.yaml`) is convenient for a self-contained
stack. If the company already operates an IdP, prefer brokering or replacing:

- **Corporate Keycloak exists** — point `authentication.oidc.keycloak.url` at it, create the `deephaven`
  and `order-simulator` clients there, and delete the in-cluster Keycloak.
- **Windows SSO** — federate this Keycloak to Active Directory (LDAP + Kerberos/SPNEGO) or broker to
  Entra ID via OIDC, so browser users never see a Keycloak login form.

## Apply order

```bash
kubectl apply -f 00-namespace.yaml
kubectl -n deephaven create configmap keycloak-realm \
  --from-file=deephaven_realm.json=../../deephaven-keycloak-oidc-server/docker/keycloak/deephaven_realm.json
# create real secrets (see 40-secrets.example.yaml for the expected keys)
kubectl apply -f 10-keycloak.yaml -f 20-deephaven.yaml -f 30-ingress.yaml
```
