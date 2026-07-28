package io.deephaven.oidc.entra;

import io.deephaven.auth.AuthContext;
import io.deephaven.base.log.LogOutput;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * {@link AuthContext} carrying the authenticated Entra ID principal: the stable object id
 * ({@code oid} claim), a human-readable username ({@code preferred_username}/{@code upn}, or the
 * client/app id for service principals), and the role-like claims ({@code roles} app roles, else
 * {@code groups}).
 *
 * <p>Deephaven Community's default authorization permits all operations regardless of context, so
 * this context is primarily for auditing/logging today — and the hook point for a custom
 * {@code AuthorizationProvider} later (see docs/oidc/ENTRA-IMPLEMENTATION-PLAN.md, Phase 2 notes).
 */
public final class EntraUserAuthContext extends AuthContext {

    private final String userId;
    private final String username;
    private final Set<String> roles;

    public EntraUserAuthContext(String userId, String username, Set<String> roles) {
        this.userId = Objects.requireNonNull(userId, "userId");
        this.username = username == null || username.isBlank() ? userId : username;
        this.roles = roles == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(roles));
    }

    /** Stable Entra object id ({@code oid} claim; falls back to {@code sub} at construction site). */
    public String userId() {
        return userId;
    }

    /** {@code preferred_username} / {@code upn}, or the app id for service principals. */
    public String username() {
        return username;
    }

    /** Role-like claims from the token: {@code roles} (app roles), else {@code groups}. */
    public Set<String> roles() {
        return roles;
    }

    @Override
    public LogOutput append(LogOutput logOutput) {
        return logOutput.append("entra-user:").append(username);
    }
}
