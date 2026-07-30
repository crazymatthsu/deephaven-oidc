package io.deephaven.oidc.demo.client;

import io.deephaven.client.impl.BarrageSession;
import io.deephaven.client.impl.BarrageSubscription;
import io.deephaven.client.impl.TableHandle;
import io.deephaven.engine.liveness.LivenessScopeStack;
import io.deephaven.engine.table.Table;
import io.deephaven.engine.table.TableUpdate;
import io.deephaven.engine.table.impl.InstrumentedTableUpdateListener;
import io.deephaven.engine.util.TableTools;
import io.deephaven.extensions.barrage.BarrageSubscriptionOptions;
import io.deephaven.oidc.demo.common.AppConfig;
import io.deephaven.oidc.demo.common.AppConfig.AuthProvider;
import io.deephaven.oidc.demo.common.DeephavenSessions;
import io.deephaven.oidc.demo.common.EntraTokenClient;
import io.deephaven.oidc.demo.common.KeycloakTokenClient;
import io.deephaven.oidc.demo.common.RefreshingToken;
import io.deephaven.util.SafeCloseable;
import io.deephaven.util.annotations.ReferentialIntegrity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * Logs in as a demo user, inspects roles on the access token, and subscribes over Barrage to the
 * entitled orders view.
 *
 * <p>Authentication:
 * <ul>
 *   <li>{@code AUTH_PROVIDER=keycloak} (default) — Keycloak password grant</li>
 *   <li>{@code AUTH_PROVIDER=entra} — MSAL against Entra ID; the flow is chosen by
 *       {@code ENTRA_USER_FLOW}:
 *       <ul>
 *         <li>{@code devicecode} (default) — prints a URL + code, sign in from any browser and approve
 *             with Microsoft Authenticator (MFA). No password is passed to this program.</li>
 *         <li>{@code interactive} — opens the system browser (auth-code + PKCE); also MFA-capable.</li>
 *         <li>{@code ropc} — legacy username/password; cannot satisfy MFA, for test tenants only.</li>
 *       </ul></li>
 * </ul>
 *
 * <p>Run as different users to see row-level security: alice → US, bob → EMEA, carol → all.
 */
public final class OrderSubscriber {

    /** Role claim value → application field published by the server-side orders app. First match wins. */
    private static final Map<String, String> VIEW_BY_ROLE = new LinkedHashMap<>();
    static {
        VIEW_BY_ROLE.put("dh-admin", "orders_all");
        VIEW_BY_ROLE.put("trader-us", "orders_us");
        VIEW_BY_ROLE.put("trader-emea", "orders_emea");
    }

    public static void main(String[] args) throws Exception {
        String user = argValue(args, "--user", System.getenv().getOrDefault("DH_USER", "alice"));
        String password = argValue(args, "--password", System.getenv().getOrDefault("DH_PASSWORD", user));

        AppConfig config = AppConfig.fromEnv();
        System.out.println("Order subscriber starting with " + config);

        // Refreshed automatically near expiry (silent MSAL renewal — no new sign-in/MFA while the
        // refresh token is valid); presented on every (re)connect.
        RefreshingToken token = tokenFor(config, user, password);

        // First acquisition happens here; roles pick the view once, at startup.
        String accessToken = token.get();
        List<String> roles = config.authProvider() == AuthProvider.ENTRA
                ? EntraTokenClient.rolesFromToken(accessToken)
                : KeycloakTokenClient.realmRoles(accessToken);
        System.out.println("Authenticated with roles " + roles);

        String view = VIEW_BY_ROLE.entrySet().stream()
                .filter(e -> roles.contains(e.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("User '" + user + "' holds none of the entitled roles "
                        + VIEW_BY_ROLE.keySet() + "; nothing to subscribe to. "
                        + "For Entra, assign app roles or groups matching these names."));
        System.out.println("Entitled view: " + view);

        long backoffMs = 5_000;
        while (true) {
            try {
                subscribeUntilFailure(config, token, view);
                System.err.println("Subscription lost.");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                System.err.println("Connection failed: " + e.getMessage());
            }
            System.err.println("Reconnecting in " + backoffMs / 1000 + "s with a current token...");
            Thread.sleep(backoffMs);
            backoffMs = Math.min(backoffMs * 2, 60_000);
        }
    }

    private static RefreshingToken tokenFor(AppConfig config, String user, String password) {
        if (config.authProvider() == AuthProvider.ENTRA) {
            String presetToken = System.getenv("ENTRA_ACCESS_TOKEN");
            if (presetToken != null && !presetToken.isBlank()) {
                // Testing/advanced hook: pre-acquired token; cannot be refreshed at expiry.
                System.out.println("Using pre-acquired Entra access token (ENTRA_ACCESS_TOKEN; no auto-refresh)");
                return RefreshingToken.fixed(presetToken.trim());
            }
            EntraTokenClient tokens = new EntraTokenClient(config);
            if (config.entraUserFlow() == AppConfig.UserFlow.ROPC) {
                // Legacy, MFA-incapable fallback for test tenants; re-grants with the same credentials.
                return RefreshingToken.of(() -> {
                    EntraTokenClient.Token t = tokens.usernamePasswordGrant(user, password);
                    return new RefreshingToken.Snapshot(t.accessToken(), t.expiresAt());
                });
            }
            // MFA-capable flows (device code / interactive): first acquisition signs in via the
            // configured flow; renewals are silent through the shared MSAL cache.
            return tokens.userToken();
        }

        KeycloakTokenClient tokens = new KeycloakTokenClient(config);
        return RefreshingToken.of(() -> {
            KeycloakTokenClient.Token t = tokens.passwordGrant(user, password);
            return new RefreshingToken.Snapshot(t.accessToken(), t.expiresAt());
        });
    }

    /** Connects, subscribes, and streams updates until the subscription or connection fails. */
    private static void subscribeUntilFailure(AppConfig config, RefreshingToken token, String view)
            throws Exception {
        CountDownLatch connectionLost = new CountDownLatch(1);
        try (DeephavenSessions sessions = DeephavenSessions.connect(config);
                BarrageSession session = sessions.newSession(token);
                SafeCloseable ignoredContext = sessions.openExecutionContext();
                SafeCloseable ignoredScope = LivenessScopeStack.open();
                TableHandle handle = OrdersSchema.fetch(session, config.applicationId(), view)) {

            BarrageSubscription subscription =
                    session.subscribe(handle, BarrageSubscriptionOptions.builder().build());
            Table table = subscription.entireTable().get();
            System.out.println("Subscribed to '" + view + "': " + table.size() + " row(s) initially");
            TableTools.show(table);

            table.addUpdateListener(new InstrumentedTableUpdateListener("orders-listener") {
                @ReferentialIntegrity
                final Table tableRef = table;
                {
                    manage(tableRef);
                }

                @Override
                protected void destroy() {
                    super.destroy();
                    tableRef.removeUpdateListener(this);
                }

                @Override
                protected void onFailureInternal(Throwable originalException, Entry sourceEntry) {
                    System.err.println("Subscription failed: " + originalException.getMessage());
                    connectionLost.countDown();
                }

                @Override
                public void onUpdate(TableUpdate upstream) {
                    System.out.println("\nUpdate: +" + upstream.added().size() + " added, ~"
                            + upstream.modified().size() + " modified, -" + upstream.removed().size()
                            + " removed; now " + tableRef.size() + " row(s)");
                    TableTools.show(tableRef);
                }
            });

            System.out.println("Listening for updates (Ctrl-C to stop)...");
            connectionLost.await();
        }
    }

    private static String argValue(String[] args, String name, String defaultValue) {
        for (int i = 0; i < args.length - 1; i++) {
            if (name.equals(args[i])) {
                return args[i + 1];
            }
        }
        return defaultValue;
    }

    private OrderSubscriber() {}
}
