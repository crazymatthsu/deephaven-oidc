# EKS deployment — direct Entra ID variant (roadmap Phase 6)

The direct-Entra counterpart of [`deploy/eks`](../eks): same gRPC-aware ALB/TLS architecture,
minus the entire Keycloak footprint. [`deploy/eks/DESIGN.md`](../eks/DESIGN.md) still applies for
everything auth-agnostic (load balancer behavior, network hardening ladder, sizing).

## What's different vs the Keycloak variant

| | Keycloak variant | This variant |
|---|---|---|
| In-cluster IdP | Keycloak Deployment + Service + Ingress + DB concerns | **None** — Entra ID is the IdP |
| Public hostnames | `deephaven.…` + `auth.…` | `deephaven.…` only |
| Server secrets | Keycloak admin/DB credentials | **None** (tenant/client ids aren't secrets) |
| Server image | `deephaven-keycloak-oidc-server/docker/deephaven` | Entra image (repo-root build context; bakes the fat jar, web login plugin, and `EntraServerMain`) |
| Console | Disabled outright (no authz layer) | **Enabled for superusers only** — role-based enforcement is in the server; disable entirely for defense-in-depth if preferred |
| Row-level security | Script-level (cooperative) | **Enforced server-side** by Entra roles |
| Extra egress | — | HTTPS to `login.microsoftonline.com` (discovery/JWKS) |

## Files

| File | Contents |
|---|---|
| `00-namespace.yaml` | `deephaven-entra` namespace |
| `10-config.yaml` | ConfigMap: tenant, audience (bare GUID!), superuser roles, SPA client id/scope |
| `20-deephaven.yaml` | Deployment + Service; START_OPTS assembled from the ConfigMap via `$(VAR)` expansion |
| `30-ingress.yaml` | Single ALB ingress (TLS/ACM, gRPC target group, long idle timeout) |
| `40-pubsub-secret.example.yaml` | Optional — only if a pub-sub daemon runs in-cluster |

## Deploy

1. **Build & push the image** (repo-root context — it needs the Gradle fat jar and shared app):

   ```bash
   ./gradlew :deephaven-entra-oidc-server:fatJar
   podman build -f deephaven-entra-oidc-server/docker/deephaven/Dockerfile \
       -t <acct>.dkr.ecr.<region>.amazonaws.com/deephaven-entra-oidc:0.39.4 .
   podman push <acct>.dkr.ecr.<region>.amazonaws.com/deephaven-entra-oidc:0.39.4
   ```

2. **Fill placeholders**: ConfigMap values in `10-config.yaml` (see the
   [tenant setup guide](../../docs/oidc/entra-tenant-setup-guide.md) — audience is the API app's
   **bare client GUID**), image in `20-deephaven.yaml`, ACM cert ARN + hostname in
   `30-ingress.yaml`.

3. **Update the Entra app registration for the public URL** — on `deephaven-users`, add SPA
   redirect URIs for the real hostname:
   `https://deephaven.example.com/ide/` and `https://deephaven.example.com/iframe/widget/`
   (exact match, trailing slashes; Entra requires HTTPS off-localhost). Keep or drop the
   localhost URIs as your dev workflow dictates.

4. **Apply**:

   ```bash
   kubectl apply -f deploy/eks-entra/
   ```

5. **Verify** (mirrors the [live validation](../../docs/oidc/entra-live-validation-results.md)):
   browse to `https://deephaven.example.com/ide` → Entra sign-in / silent SSO + Authenticator →
   role-filtered access; `kubectl logs deploy/deephaven -n deephaven-entra | grep "Entra login"`
   shows identities and roles; a role-less user sees nothing.

## Notes

- **Egress**: the server fetches `login.microsoftonline.com` discovery/JWKS at startup (crash-loops
  with a clear error until reachable) and on signing-key rotation. If you run restrictive
  NetworkPolicies/egress firewalls, allow 443 to Microsoft's login endpoints.
- **Clients from on-prem**: the Java daemon/subscriber flows work unchanged against the public
  hostname (`DH_HOST=deephaven.example.com DH_PORT=443 DH_TLS=true`); tokens auto-refresh and
  clients reconnect (Phase 3), so pod restarts and deploys are absorbed.
- **In-cluster daemon**: prefer AWS Secrets Manager + External Secrets/CSI over the raw Secret in
  `40-pubsub-secret.example.yaml`; one confidential app per team
  ([guide §6.6](../../docs/oidc/entra-tenant-setup-guide.md)).
- **Not load-tested / not applied to a live cluster** — manifests are structurally validated and
  mirror the proven local compose stack; treat the first cluster rollout as a staging exercise.
