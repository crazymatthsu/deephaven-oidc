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
import io.deephaven.oidc.demo.common.DeephavenSessions;
import io.deephaven.oidc.demo.common.KeycloakTokenClient;
import io.deephaven.oidc.demo.common.KeycloakTokenClient.Token;
import io.deephaven.util.SafeCloseable;
import io.deephaven.util.annotations.ReferentialIntegrity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * Logs in to Keycloak as a demo user (password grant), inspects the realm roles carried by the access token, and
 * subscribes over Barrage to the orders view that role is entitled to. Run as different users to see row-level
 * security in action: alice sees only US orders, bob only EMEA, carol everything.
 */
public final class OrderSubscriber {

    /** Realm role → application field published by the server-side orders app. First match wins. */
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

        KeycloakTokenClient tokens = new KeycloakTokenClient(config);
        Token token = tokens.passwordGrant(user, password);
        List<String> roles = KeycloakTokenClient.realmRoles(token.accessToken());
        System.out.println("Authenticated to Keycloak as '" + user + "' with realm roles " + roles);

        String view = VIEW_BY_ROLE.entrySet().stream()
                .filter(e -> roles.contains(e.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("User '" + user + "' holds none of the entitled roles "
                        + VIEW_BY_ROLE.keySet() + "; nothing to subscribe to."));
        System.out.println("Entitled view for '" + user + "': " + view);

        CountDownLatch done = new CountDownLatch(1);
        try (DeephavenSessions sessions = DeephavenSessions.connect(config);
                BarrageSession session = sessions.newSession(token.accessToken());
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
                    System.err.println("Subscription failed:");
                    originalException.printStackTrace();
                    done.countDown();
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
            done.await();
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
