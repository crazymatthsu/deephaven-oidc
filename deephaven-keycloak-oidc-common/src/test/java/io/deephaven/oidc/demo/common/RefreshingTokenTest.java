package io.deephaven.oidc.demo.common;

import io.deephaven.oidc.demo.common.RefreshingToken.Snapshot;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RefreshingTokenTest {

    private static Snapshot expiringIn(String token, Duration lifetime) {
        return new Snapshot(token, Instant.now().plus(lifetime));
    }

    @Test
    void refreshesOnFirstUseThenServesCached() {
        AtomicInteger calls = new AtomicInteger();
        RefreshingToken token = RefreshingToken.of(Duration.ofMinutes(2),
                () -> expiringIn("t" + calls.incrementAndGet(), Duration.ofHours(1)));

        assertEquals("t1", token.get());
        assertEquals("t1", token.get()); // far from expiry — no second refresh
        assertEquals(1, calls.get());
    }

    @Test
    void refreshesWhenWithinMarginOfExpiry() {
        AtomicInteger calls = new AtomicInteger();
        // Each issued token lives only 1 minute; margin is 2 minutes → every get() refreshes.
        RefreshingToken token = RefreshingToken.of(Duration.ofMinutes(2),
                () -> expiringIn("t" + calls.incrementAndGet(), Duration.ofMinutes(1)));

        assertEquals("t1", token.get());
        assertEquals("t2", token.get());
        assertEquals(2, calls.get());
    }

    @Test
    void refreshesWhenAlreadyExpired() {
        AtomicInteger calls = new AtomicInteger();
        RefreshingToken token = RefreshingToken.of(Duration.ofMinutes(2),
                () -> calls.incrementAndGet() == 1
                        ? new Snapshot("stale", Instant.now().minusSeconds(60))
                        : expiringIn("fresh", Duration.ofHours(1)));

        assertEquals("stale", token.get()); // issued already-expired (pathological refresher)
        assertEquals("fresh", token.get()); // expired → refreshed
    }

    @Test
    void getFreshForcesRefreshRegardlessOfExpiry() {
        AtomicInteger calls = new AtomicInteger();
        RefreshingToken token = RefreshingToken.of(Duration.ofMinutes(2),
                () -> expiringIn("t" + calls.incrementAndGet(), Duration.ofHours(1)));

        assertEquals("t1", token.get());
        assertEquals("t2", token.getFresh()); // still an hour of life left, but forced
        assertEquals("t2", token.get());      // new snapshot now cached
        assertEquals(2, calls.get());
    }

    @Test
    void fixedTokenNeverRefreshes() {
        RefreshingToken token = RefreshingToken.fixed("static-token");
        assertEquals("static-token", token.get());
        assertEquals("static-token", token.getFresh());
        assertEquals("static-token", token.get());
    }
}
