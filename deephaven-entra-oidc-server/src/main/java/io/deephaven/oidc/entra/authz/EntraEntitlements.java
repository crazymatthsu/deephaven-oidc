package io.deephaven.oidc.entra.authz;

import io.deephaven.auth.AuthContext;
import io.deephaven.oidc.entra.EntraUserAuthContext;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Entitlement policy shared by the authorization hooks.
 *
 * <p>Published tables opt into role-gating via the {@value #ENTITLED_ROLES_ATTRIBUTE} table
 * attribute — a comma-separated list of Entra role claims allowed to access the table (see the
 * orders demo app, {@code orders_app.py}). Rules:
 * <ul>
 *   <li>{@code SuperUser} (and any non-Entra context, e.g. the instance context used by server
 *       internals) bypasses entitlement checks entirely.</li>
 *   <li>An {@link EntraUserAuthContext} may access a table iff one of its roles appears in the
 *       table's {@value #ENTITLED_ROLES_ATTRIBUTE} attribute.</li>
 *   <li>Objects without the attribute are <b>denied</b> to entitlement-scoped users
 *       (fail-closed).</li>
 * </ul>
 */
public final class EntraEntitlements {

    /** Table attribute naming the roles entitled to the table (comma-separated). */
    public static final String ENTITLED_ROLES_ATTRIBUTE = "EntraEntitledRoles";

    private EntraEntitlements() {}

    /** True when the context is NOT subject to entitlement checks (superuser/internal). */
    public static boolean bypassesEntitlements(AuthContext context) {
        return !(context instanceof EntraUserAuthContext);
    }

    /** Parses the comma-separated attribute value into a role set. */
    public static Set<String> parseRoles(Object attributeValue) {
        if (attributeValue == null) {
            return Set.of();
        }
        return Arrays.stream(String.valueOf(attributeValue).split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** True when any of the user's roles appears in the allowed set. */
    public static boolean isEntitled(Set<String> userRoles, Set<String> allowedRoles) {
        if (userRoles == null || allowedRoles == null || allowedRoles.isEmpty()) {
            return false;
        }
        return userRoles.stream().anyMatch(allowedRoles::contains);
    }

    /** Full check: bypass, else role intersection against a raw attribute value. */
    public static boolean isEntitled(AuthContext context, Object attributeValue) {
        if (bypassesEntitlements(context)) {
            return true;
        }
        return isEntitled(((EntraUserAuthContext) context).roles(), parseRoles(attributeValue));
    }
}
