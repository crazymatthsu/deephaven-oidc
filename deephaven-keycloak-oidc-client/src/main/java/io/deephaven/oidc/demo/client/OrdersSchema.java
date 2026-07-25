package io.deephaven.oidc.demo.client;

import io.deephaven.client.impl.BarrageSession;
import io.deephaven.client.impl.TableHandle;
import io.deephaven.qst.table.TicketTable;

/**
 * Helpers for resolving tables published by the server's orders application
 * ({@code deephaven-keycloak-oidc-server/docker/deephaven/app.d/orders_app.py}).
 */
final class OrdersSchema {

    /**
     * Resolves a table published by the orders application. App-mode scripts publish fields under the application
     * ticket namespace ({@code a/<appId>/f/<field>}); fall back to the query-scope namespace ({@code s/<field>}) so
     * the clients also work against a console session that ran the script manually.
     */
    static TableHandle fetch(BarrageSession session, String applicationId, String fieldName) throws InterruptedException {
        try {
            return session.session().execute(TicketTable.fromApplicationField(applicationId, fieldName));
        } catch (TableHandle.TableHandleException e) {
            try {
                return session.session().execute(TicketTable.fromQueryScopeField(fieldName));
            } catch (TableHandle.TableHandleException inner) {
                IllegalStateException failure = new IllegalStateException(
                        "Unable to fetch table '" + fieldName + "' as an application field of '" + applicationId
                                + "' or from the query scope. Is the orders app deployed on the server?",
                        inner);
                failure.addSuppressed(e);
                throw failure;
            }
        }
    }

    private OrdersSchema() {}
}
