package io.deephaven.oidc.entra.authz;

import io.deephaven.auth.AuthContext;
import io.deephaven.oidc.entra.EntraUserAuthContext;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntraEntitlementsTest {

    @Test
    void parsesCommaSeparatedRoles() {
        assertEquals(Set.of("trader-us", "dh-admin"),
                EntraEntitlements.parseRoles("trader-us, dh-admin"));
        assertEquals(Set.of(), EntraEntitlements.parseRoles(null));
        assertEquals(Set.of(), EntraEntitlements.parseRoles("  ,, "));
    }

    @Test
    void roleIntersection() {
        assertTrue(EntraEntitlements.isEntitled(Set.of("trader-us"), Set.of("trader-us", "dh-admin")));
        assertFalse(EntraEntitlements.isEntitled(Set.of("trader-emea"), Set.of("trader-us")));
        // No attribute / empty allowed set fails closed.
        assertFalse(EntraEntitlements.isEntitled(Set.of("trader-us"), Set.of()));
        assertFalse(EntraEntitlements.isEntitled(Set.of(), Set.of("trader-us")));
    }

    @Test
    void superuserAndNonEntraContextsBypass() {
        assertTrue(EntraEntitlements.bypassesEntitlements(new AuthContext.SuperUser()));
        assertTrue(EntraEntitlements.bypassesEntitlements(null));
        assertFalse(EntraEntitlements.bypassesEntitlements(
                new EntraUserAuthContext("oid", "alice", Set.of("trader-us"))));
    }

    @Test
    void fullCheckAgainstAttributeValue() {
        AuthContext alice = new EntraUserAuthContext("oid", "alice", Set.of("trader-us"));
        assertTrue(EntraEntitlements.isEntitled(alice, "trader-us,dh-admin"));
        assertFalse(EntraEntitlements.isEntitled(alice, "trader-emea"));
        assertFalse(EntraEntitlements.isEntitled(alice, null));
        assertTrue(EntraEntitlements.isEntitled(new AuthContext.SuperUser(), null));
    }
}
