package io.deephaven.oidc.demo.common;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Long-lived access-token holder for clients that outlive a single token (Phase 3, token
 * lifecycle). Wraps a provider-specific refresher (MSAL silent renewal / client-credentials
 * re-grant / Keycloak grant) and re-invokes it only when the cached token is within
 * {@code refreshMargin} of expiry — callers just ask for {@link #get()} whenever they are about
 * to (re)connect.
 *
 * <p>Deephaven sessions maintain themselves on rotating session tokens after the handshake, so
 * the bearer token matters at connect/reconnect time; there is no need to push fresh tokens into
 * a live session.
 *
 * <p>Thread-safe; refreshes are serialized.
 */
public final class RefreshingToken {

    /** Provider-agnostic view of an issued token. */
    public record Snapshot(String accessToken, Instant expiresAt) {
        public Snapshot {
            Objects.requireNonNull(accessToken, "accessToken");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }

    /** Default headroom: refresh when less than this much lifetime remains. */
    public static final Duration DEFAULT_REFRESH_MARGIN = Duration.ofMinutes(2);

    private final Supplier<Snapshot> refresher;
    private final Duration refreshMargin;
    private Snapshot current;

    private RefreshingToken(Supplier<Snapshot> refresher, Duration refreshMargin) {
        this.refresher = Objects.requireNonNull(refresher, "refresher");
        this.refreshMargin = Objects.requireNonNull(refreshMargin, "refreshMargin");
    }

    /** A token that refreshes via {@code refresher} whenever within {@code margin} of expiry. */
    public static RefreshingToken of(Duration margin, Supplier<Snapshot> refresher) {
        return new RefreshingToken(refresher, margin);
    }

    /** A token with the default refresh margin. */
    public static RefreshingToken of(Supplier<Snapshot> refresher) {
        return new RefreshingToken(refresher, DEFAULT_REFRESH_MARGIN);
    }

    /**
     * A fixed token that can never be refreshed (the {@code ENTRA_ACCESS_TOKEN} testing hook).
     * {@link #getFresh()} returns the same value; expiry-driven refresh never triggers.
     */
    public static RefreshingToken fixed(String accessToken) {
        Snapshot constant = new Snapshot(accessToken, Instant.MAX);
        RefreshingToken token = new RefreshingToken(() -> constant, DEFAULT_REFRESH_MARGIN);
        token.current = constant;
        return token;
    }

    /** The current access token, transparently refreshed when near/past expiry. */
    public synchronized String get() {
        if (current == null || Instant.now().plus(refreshMargin).isAfter(current.expiresAt())) {
            current = Objects.requireNonNull(refresher.get(), "refresher returned null");
        }
        return current.accessToken();
    }

    /**
     * Forces a refresh regardless of expiry — for retrying after the server rejects a token
     * (e.g. UNAUTHENTICATED on reconnect with clock skew or a revoked token).
     */
    public synchronized String getFresh() {
        Snapshot refreshed = refresher.get();
        if (refreshed != null) {
            current = refreshed;
        }
        return current.accessToken();
    }
}
