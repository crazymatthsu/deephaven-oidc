package io.deephaven.oidc.entra.authz;

import io.deephaven.auth.AuthContext;
import io.deephaven.auth.codegen.impl.ConsoleServiceAuthWiring;
import io.deephaven.proto.backplane.script.grpc.BindTableToVariableRequest;
import io.deephaven.proto.backplane.script.grpc.ExecuteCommandRequest;
import io.deephaven.proto.backplane.script.grpc.StartConsoleRequest;
import io.deephaven.proto.util.Exceptions;
import com.google.rpc.Code;

/**
 * Console access restricted to superusers. Entitlement-scoped users could otherwise run arbitrary
 * code and reach the unfiltered tables, defeating the ticket-level entitlements.
 */
public final class EntraConsoleAuthWiring extends ConsoleServiceAuthWiring.AllowAll {

    @Override
    public void onMessageReceivedStartConsole(AuthContext authContext, StartConsoleRequest request) {
        requireSuperUser(authContext);
    }

    @Override
    public void onMessageReceivedExecuteCommand(AuthContext authContext, ExecuteCommandRequest request) {
        requireSuperUser(authContext);
    }

    @Override
    public void onMessageReceivedBindTableToVariable(AuthContext authContext,
            BindTableToVariableRequest request) {
        requireSuperUser(authContext);
    }

    private static void requireSuperUser(AuthContext authContext) {
        if (!EntraEntitlements.bypassesEntitlements(authContext)) {
            throw Exceptions.statusRuntimeException(Code.PERMISSION_DENIED,
                    "The console is restricted to administrators on this deployment");
        }
    }
}
