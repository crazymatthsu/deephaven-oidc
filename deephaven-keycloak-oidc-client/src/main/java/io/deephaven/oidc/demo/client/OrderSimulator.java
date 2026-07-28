package io.deephaven.oidc.demo.client;

import io.deephaven.client.impl.BarrageSession;
import io.deephaven.client.impl.TableHandle;
import io.deephaven.oidc.demo.common.AppConfig;
import io.deephaven.oidc.demo.common.AppConfig.AuthProvider;
import io.deephaven.oidc.demo.common.DeephavenSessions;
import io.deephaven.oidc.demo.common.EntraTokenClient;
import io.deephaven.oidc.demo.common.KeycloakTokenClient;
import io.deephaven.qst.column.header.ColumnHeader;
import io.deephaven.qst.table.NewTable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * Publishes a stream of mock orders into the server's keyed {@code orders} input table. Because the table is keyed on
 * {@code OrderId}, re-publishing an existing id updates that row in place (status transitions, fills), while new ids
 * append.
 *
 * <p>Authentication:
 * <ul>
 *   <li>{@code AUTH_PROVIDER=keycloak} (default) — Keycloak client-credentials for {@code order-simulator}</li>
 *   <li>{@code AUTH_PROVIDER=entra} — MSAL client-credentials against Entra ID
 *       ({@code ENTRA_TENANT_ID}, {@code ENTRA_CLIENT_ID}, {@code ENTRA_CLIENT_SECRET}, {@code ENTRA_SCOPE})</li>
 * </ul>
 */
public final class OrderSimulator {

    private static final Map<String, List<String>> SYMBOLS_BY_REGION = Map.of(
            "US", List.of("AAPL", "MSFT", "NVDA", "AMZN"),
            "EMEA", List.of("SAP.DE", "ASML.AS", "AZN.L", "NESN.SW"),
            "APAC", List.of("7203.T", "005930.KS", "9988.HK", "BHP.AX"));
    private static final List<String> REGIONS = List.of("US", "EMEA", "APAC");
    private static final Map<String, String> TRADER_BY_REGION = Map.of(
            "US", "alice",
            "EMEA", "bob",
            "APAC", "carol");
    private static final List<String> OPEN_STATUSES = List.of("NEW", "WORKING", "PARTIAL");

    /** Orders we've already published, so a share of ticks become keyed updates instead of inserts. */
    private record LiveOrder(long orderId, String symbol, String region, String side, int qty, double price) {
    }

    public static void main(String[] args) throws Exception {
        AppConfig config = AppConfig.fromEnv();
        System.out.println("Order simulator starting with " + config);

        String accessToken = acquireAccessToken(config);

        try (DeephavenSessions sessions = DeephavenSessions.connect(config);
                BarrageSession session = sessions.newSession(accessToken);
                TableHandle orders = OrdersSchema.fetch(session, config.applicationId(), "orders")) {
            System.out.println("Connected to Deephaven; publishing orders (Ctrl-C to stop)...");
            run(sessions, session, orders);
        }
    }

    private static String acquireAccessToken(AppConfig config) {
        if (config.authProvider() == AuthProvider.ENTRA) {
            String secret = System.getenv().getOrDefault("ENTRA_CLIENT_SECRET", "");
            EntraTokenClient tokens = new EntraTokenClient(config);
            EntraTokenClient.Token token = tokens.clientCredentialsGrant(secret);
            System.out.println("Authenticated to Entra ID as confidential client '" + config.entraClientId() + "'");
            return token.accessToken();
        }

        String simClientId = System.getenv().getOrDefault("KC_SIM_CLIENT_ID", "order-simulator");
        String simSecret = System.getenv().getOrDefault("KC_SIM_CLIENT_SECRET", "order-simulator-secret");
        KeycloakTokenClient tokens = new KeycloakTokenClient(config);
        KeycloakTokenClient.Token token = tokens.clientCredentialsGrant(simClientId, simSecret);
        System.out.println("Authenticated to Keycloak as service account '" + simClientId + "'");
        return token.accessToken();
    }

    private static void run(DeephavenSessions sessions, BarrageSession session, TableHandle orders) throws Exception {
        var header = ColumnHeader.of(
                ColumnHeader.ofLong("OrderId"),
                ColumnHeader.ofString("Symbol"),
                ColumnHeader.ofString("Region"),
                ColumnHeader.ofString("Desk"),
                ColumnHeader.ofString("Trader"),
                ColumnHeader.ofString("Side"),
                ColumnHeader.ofInt("Qty"),
                ColumnHeader.ofDouble("Price"),
                ColumnHeader.ofString("Status"),
                ColumnHeader.ofInstant("LastUpdated"));

        ThreadLocalRandom random = ThreadLocalRandom.current();
        List<LiveOrder> live = new ArrayList<>();
        long nextOrderId = 10_000;
        long published = 0;

        while (true) {
            var rows = header.start(4);
            int batchSize = random.nextInt(1, 5);
            for (int i = 0; i < batchSize; i++) {
                if (!live.isEmpty() && random.nextInt(100) < 40) {
                    // Update an existing order: partial fill or terminal state.
                    LiveOrder order = live.get(random.nextInt(live.size()));
                    boolean terminal = random.nextInt(100) < 30;
                    String status = terminal ? (random.nextBoolean() ? "FILLED" : "CANCELLED")
                            : OPEN_STATUSES.get(random.nextInt(OPEN_STATUSES.size()));
                    rows.row(order.orderId(), order.symbol(), order.region(), order.region() + "-EQ",
                            TRADER_BY_REGION.get(order.region()), order.side(), order.qty(),
                            round(order.price() * random.nextDouble(0.995, 1.005)), status, Instant.now());
                    if (terminal) {
                        live.remove(order);
                    }
                } else {
                    // New order.
                    String region = REGIONS.get(random.nextInt(REGIONS.size()));
                    List<String> symbols = SYMBOLS_BY_REGION.get(region);
                    LiveOrder order = new LiveOrder(nextOrderId++, symbols.get(random.nextInt(symbols.size())),
                            region, random.nextBoolean() ? "BUY" : "SELL",
                            random.nextInt(1, 100) * 100, round(random.nextDouble(10, 500)));
                    rows.row(order.orderId(), order.symbol(), order.region(), order.region() + "-EQ",
                            TRADER_BY_REGION.get(order.region()), order.side(), order.qty(), order.price(),
                            "NEW", Instant.now());
                    live.add(order);
                }
            }
            NewTable batch = rows.newTable();
            session.addToInputTable(orders, batch, sessions.allocator()).get(10, TimeUnit.SECONDS);
            published += batch.size();
            System.out.println(Instant.now() + " published " + batch.size() + " row(s), total " + published
                    + ", live orders " + live.size());
            Thread.sleep(random.nextLong(500, 1500));
        }
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private OrderSimulator() {}
}
