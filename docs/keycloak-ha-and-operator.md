# Keycloak High Availability and Operator on EKS

**Date:** 2026-07-25  
**Context:** Research on running a shared Keycloak instance for multiple Deephaven pods, clustering for HA, and the benefits of the official Keycloak Operator.

## Should Multiple Deephaven Pods Share One Keycloak?

**Yes.** Always run a single logical Keycloak (one Service / one hostname) shared by all Deephaven pods in the namespace.

Reasons:

- All Deephaven pods must validate tokens against the **same** issuer and JWKS endpoint.
- Users, roles, and service accounts live in one realm.
- The OIDC contract requires a single identity provider URL (`authentication.oidc.keycloak.url`).

Running independent Keycloak instances would break token interoperability and the row-level security model.

You *can* scale the Keycloak Deployment to 2–3 replicas later; it remains one logical IdP behind a single Service.

## Keycloak Clustering for HA (Keycloak 26.x)

Keycloak 26 simplified clustering significantly.

### Recommended settings

| Setting | Value | Notes |
|---------|-------|-------|
| Cache mode | `ispn` | Enables distributed Infinispan caches |
| Cache stack | `jdbc-ping` (default) | Uses the shared Postgres DB for node discovery — no headless service required |
| Replicas | 2 or 3 | Sufficient for HA |
| Database | Shared RDS PostgreSQL (Multi-AZ) | Required |

`jdbc-ping` is the modern default and is preferred over the older (now deprecated) `kubernetes` / DNS_PING stack.

### Minimal changes to `10-keycloak.yaml`

- Set `replicas: 3`
- Add `KC_CACHE=ispn`
- Leave `KC_CACHE_STACK` unset (defaults to `jdbc-ping`)
- Add pod anti-affinity across availability zones
- Expose JGroups ports 7800 and 57800
- Prefer sticky sessions on the ALB / Ingress

After the initial realm import, remove `--import-realm` so subsequent pods do not re-import.

## Keycloak Operator Benefits

The official **Keycloak Operator** is the recommended production path. The Keycloak team’s load-tested HA blueprints are built on it.

### Comparison vs Plain Deployment

| Area | Plain Deployment | Keycloak Operator | Winner |
|------|------------------|-------------------|--------|
| Clustering / HA | Manual configuration of cache, ports, anti-affinity | Automatic configuration of distributed caches and node identity | Operator |
| Security | You must write NetworkPolicies | Operator automatically creates a NetworkPolicy that blocks external access to clustering ports | Operator |
| Rolling upgrades | Easy to break the cluster | Safer rolling updates with readiness gates | Operator |
| Configuration style | Long list of environment variables | Declarative `Keycloak` Custom Resource | Operator |
| Day-2 operations | Manual reconciliation | Continuous reconciliation to the desired state | Operator |
| Official HA guidance | Unsupported | Official load-tested single-cluster and multi-cluster guides | Operator |
| Startup complexity | Lower | Higher (must install Operator first) | Deployment |

### When to use the Operator

- Production EKS with 2–3 Keycloak replicas → **Use the Operator**
- You want the official HA configuration → **Use the Operator**
- Simple single-replica or pure learning → Plain Deployment is fine

### Key Operator features relevant to this repo

- Automatic NetworkPolicy protecting Infinispan/JGroups ports
- Clean handling of hostname, TLS, database connection, and instance count
- Better upgrade path across Keycloak versions
- Easier adoption of advanced features (multi-site, remote cache, metrics)

## Recommendation for This Repository

1. Keep a **single logical Keycloak** shared by all Deephaven pods.
2. For production HA, prefer the **Keycloak Operator** over a hand-maintained Deployment.
3. If staying with a plain Deployment for now, enable `KC_CACHE=ispn` + `jdbc-ping` and scale to 2–3 replicas with anti-affinity and sticky sessions.

## Related Files

- `deploy/eks/10-keycloak.yaml` – current single-replica Deployment
- `deploy/eks/DESIGN.md` – overall security and placement guidance
- `deploy/eks/20-deephaven.yaml` – Deephaven side (points at the shared Keycloak URL)
