package io.deephaven.oidc.entra.authz;

import io.deephaven.auth.codegen.impl.ConsoleServiceAuthWiring;
import io.deephaven.auth.codegen.impl.InputTableServiceContextualAuthWiring;
import io.deephaven.server.auth.AllowAllAuthorizationProvider;
import io.deephaven.server.session.TicketResolver;

import javax.inject.Inject;

/**
 * Role-based authorization for the direct-Entra Deephaven server: ticket resolution filtered by
 * the {@link EntraEntitlements#ENTITLED_ROLES_ATTRIBUTE} table attribute, input-table writes
 * gated the same way, and consoles restricted to superusers. Everything else stays allow-all —
 * Deephaven Community's baseline — because those services expose no entitlement-scoped data.
 */
public final class EntraAuthorizationProvider extends AllowAllAuthorizationProvider {

    private final EntraTicketAuthorization ticketAuthorization = new EntraTicketAuthorization();
    private final EntraConsoleAuthWiring consoleAuthWiring = new EntraConsoleAuthWiring();
    private final EntraInputTableAuthWiring inputTableAuthWiring = new EntraInputTableAuthWiring();

    @Inject
    public EntraAuthorizationProvider() {}

    @Override
    public TicketResolver.Authorization getTicketResolverAuthorization() {
        return ticketAuthorization;
    }

    @Override
    public ConsoleServiceAuthWiring getConsoleServiceAuthWiring() {
        return consoleAuthWiring;
    }

    @Override
    public InputTableServiceContextualAuthWiring getInputTableServiceContextualAuthWiring() {
        return inputTableAuthWiring;
    }
}
