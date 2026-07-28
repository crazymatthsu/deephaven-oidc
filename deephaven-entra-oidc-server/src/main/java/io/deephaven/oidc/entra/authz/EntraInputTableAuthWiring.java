package io.deephaven.oidc.entra.authz;

import io.deephaven.auth.AuthContext;
import io.deephaven.auth.codegen.impl.InputTableServiceContextualAuthWiring;
import io.deephaven.engine.table.Table;
import io.deephaven.proto.backplane.grpc.AddTableRequest;
import io.deephaven.proto.backplane.grpc.DeleteTableRequest;
import io.deephaven.proto.util.Exceptions;
import com.google.rpc.Code;

import java.util.List;

/**
 * Input-table mutations (the simulator's keyed upserts, IDE entitlement edits) require the caller
 * to be entitled to every target table — superusers bypass, others need a role listed in the
 * table's {@link EntraEntitlements#ENTITLED_ROLES_ATTRIBUTE} attribute (the demo grants the
 * {@code writer} app role on the raw {@code orders} table).
 */
public final class EntraInputTableAuthWiring implements InputTableServiceContextualAuthWiring {

    @Override
    public void checkPermissionAddTableToInputTable(AuthContext authContext, AddTableRequest request,
            List<Table> sourceTables) {
        checkEntitled(authContext, sourceTables);
    }

    @Override
    public void checkPermissionDeleteTableFromInputTable(AuthContext authContext, DeleteTableRequest request,
            List<Table> sourceTables) {
        checkEntitled(authContext, sourceTables);
    }

    private static void checkEntitled(AuthContext authContext, List<Table> tables) {
        if (EntraEntitlements.bypassesEntitlements(authContext)) {
            return;
        }
        for (Table table : tables) {
            // sourceTables contains both the mutation target and the caller's uploaded batch;
            // only the target carries the INPUT_TABLE attribute and needs gating.
            if (table.getAttribute(Table.INPUT_TABLE_ATTRIBUTE) == null) {
                continue;
            }
            if (!EntraEntitlements.isEntitled(authContext,
                    table.getAttribute(EntraEntitlements.ENTITLED_ROLES_ATTRIBUTE))) {
                throw Exceptions.statusRuntimeException(Code.PERMISSION_DENIED,
                        "Not entitled to modify this input table");
            }
        }
    }
}
