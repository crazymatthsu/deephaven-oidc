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

        String accessToken;
        List<String> roles;
        String presetToken = System.getenv("ENTRA_ACCESS_TOKEN");
        if (config.authProvider() == AuthProvider.ENTRA && presetToken != null && !presetToken.isBlank()) {
            // Testing/advanced hook: use a pre-acquired token (e.g. minted against a mock issuer,
            // or obtained out-of-band) instead of an MSAL flow.
            accessToken = presetToken.trim();
            roles = EntraTokenClient.rolesFromToken(accessToken);
            System.out.println("Using pre-acquired Entra access token (ENTRA_ACCESS_TOKEN) with roles " + roles);
        } else if (config.authProvider() == AuthProvider.ENTRA) {
            EntraTokenClient tokens = new EntraTokenClient(config);
            EntraTokenClient.Token token = switch (config.entraUserFlow()) {
                // MFA-capable flows: identity comes from whoever completes the browser sign-in;
                // --user/DH_USER and passwords are not used.
                case DEVICE_CODE -> tokens.deviceCodeGrant();
                case INTERACTIVE -> tokens.interactiveGrant();
                // Legacy, MFA-incapable fallback for test tenants.
                case ROPC -> tokens.usernamePasswordGrant(user, password);
            };
            accessToken = token.accessToken();
            roles = EntraTokenClient.rolesFromToken(accessToken);
            String who = token.username().isBlank() ? user : token.username();
            System.out.println("Authenticated to Entra ID as '" + who + "' with roles " + roles);
        } else {
            KeycloakTokenClient tokens = new KeycloakTokenClient(config);
            KeycloakTokenClient.Token token = tokens.passwordGrant(user, password);
            accessToken = token.accessToken();
            roles = KeycloakTokenClient.realmRoles(accessToken);
            System.out.println("Authenticated to Keycloak as '" + user + "' with realm roles " + roles);
        }

        String view = VIEW_BY_ROLE.entrySet().stream()
                .filter(e -> roles.contains(e.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("User '" + user + "' holds none of the entitled roles "
                        + VIEW_BY_ROLE.keySet() + "; nothing to subscribe to. "
                        + "For Entra, assign app roles or groups matching these names."));
        System.out.println("Entitled view for '" + user + "': " + view);

        CountDownLatch done = new CountDownLatch(1);
        try (DeephavenSessions sessions = DeephavenSessions.connect(config);
                BarrageSession session = sessions.newSession(accessToken);
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
