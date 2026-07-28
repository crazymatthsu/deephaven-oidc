package io.deephaven.oidc.entra.authz;

import io.deephaven.auth.AuthContext;
import io.deephaven.engine.context.ExecutionContext;
import io.deephaven.engine.table.Table;
import io.deephaven.oidc.entra.EntraUserAuthContext;
import io.deephaven.server.session.TicketResolver;
import org.apache.arrow.flight.impl.Flight;

import java.nio.ByteBuffer;
import java.util.logging.Logger;

/**
 * Role-based {@link TicketResolver.Authorization}: the universal chokepoint every ticket
 * resolution funnels through — Flight DoGet/DoExchange (Barrage subscriptions), getFlightInfo,
 * session exports, web IDE object fetches.
 *
 * <p>Runs inside the calling session's {@link ExecutionContext} (the gRPC layer opens it around
 * every session call — see {@code SessionServiceGrpcImpl.rpcWrapper}), so the caller's
 * {@link AuthContext} is available. Returning {@code null} is the engine's documented deny idiom:
 * Flight listings silently omit the table and direct fetches surface NOT_FOUND.
 */
public final class EntraTicketAuthorization implements TicketResolver.Authorization {

    private static final Logger log = Logger.getLogger(EntraTicketAuthorization.class.getName());

    @Override
    public <T> T transform(T source) {
        if (source == null) {
            return null;
        }
        final AuthContext context = currentAuthContext();
        if (EntraEntitlements.bypassesEntitlements(context)) {
            return source;
        }
        final EntraUserAuthContext user = (EntraUserAuthContext) context;
        if (source instanceof Table) {
            final Object allowed = ((Table) source).getAttribute(EntraEntitlements.ENTITLED_ROLES_ATTRIBUTE);
            if (EntraEntitlements.isEntitled(user.roles(), EntraEntitlements.parseRoles(allowed))) {
                return source;
            }
            log.info(() -> "Denied table access: user=" + user.username() + " roles=" + user.roles()
                    + " entitledRoles=" + allowed);
            return null;
        }
        // Fail closed for entitlement-scoped users on non-table objects.
        log.info(() -> "Denied non-table object access: user=" + user.username()
                + " type=" + source.getClass().getName());
        return null;
    }

    @Override
    public void authorizePublishRequest(TicketResolver ticketResolver, ByteBuffer ticket) {
        // Publishing (BindTableToVariable / PublishFromTicket) reaches here only from consoles,
        // which the console wiring restricts to superusers; no additional gating needed.
    }

    @Override
    public void authorizePublishRequest(TicketResolver ticketResolver, Flight.FlightDescriptor descriptor) {
        // See above.
    }

    private static AuthContext currentAuthContext() {
        final ExecutionContext executionContext = ExecutionContext.getContext();
        return executionContext == null ? null : executionContext.getAuthContext();
    }
}
