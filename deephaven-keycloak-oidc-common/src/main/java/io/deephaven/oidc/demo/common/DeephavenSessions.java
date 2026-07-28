package io.deephaven.oidc.demo.common;

import io.deephaven.client.impl.BarrageSession;
import io.deephaven.client.impl.BarrageSessionFactoryConfig;
import io.deephaven.client.impl.BarrageSessionFactoryConfig.Factory;
import io.deephaven.client.impl.ClientConfig;
import io.deephaven.client.impl.SessionConfig;
import io.deephaven.engine.context.ExecutionContext;
import io.deephaven.engine.updategraph.impl.PeriodicUpdateGraph;
import io.deephaven.uri.DeephavenTarget;
import io.deephaven.util.SafeCloseable;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;

import java.net.URI;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Owns the gRPC channel plumbing shared by the demo clients and creates OIDC-authenticated
 * {@link BarrageSession}s.
 *
 * <p>The authentication handshake presents an access token to the server handler selected by
 * {@link AppConfig#deephavenAuthType()} (Keycloak {@code OidcAuthenticationHandler} or Entra
 * {@code EntraOidcAuthenticationHandler}).
 */
public final class DeephavenSessions implements AutoCloseable {

    private final AppConfig config;
    private final BufferAllocator allocator;
    private final ScheduledExecutorService scheduler;
    private final Factory factory;
    private final ExecutionContext executionContext;

    public static DeephavenSessions connect(AppConfig config) {
        return new DeephavenSessions(config);
    }

    private DeephavenSessions(AppConfig config) {
        this.config = config;
        allocator = new RootAllocator();
        scheduler = Executors.newScheduledThreadPool(4);
        ClientConfig clientConfig = ClientConfig.builder()
                .target(DeephavenTarget.of(URI.create(config.deephavenTargetUri())))
                .build();
        factory = BarrageSessionFactoryConfig.builder()
                .clientConfig(clientConfig)
                .allocator(allocator)
                .scheduler(scheduler)
                .build()
                .factory();
        // A DEFAULT update graph must exist before the client-side engine can materialize Barrage subscriptions.
        PeriodicUpdateGraph updateGraph = PeriodicUpdateGraph.newBuilder("DEFAULT").existingOrBuild();
        executionContext = ExecutionContext.newBuilder()
                .markSystemic()
                .emptyQueryScope()
                .newQueryLibrary()
                .setUpdateGraph(updateGraph)
                .build();
    }

    /** Opens a new session authenticated as the bearer of {@code accessToken}. */
    public BarrageSession newSession(String accessToken) {
        SessionConfig sessionConfig = SessionConfig.builder()
                .authenticationTypeAndValue(config.deephavenAuthType() + " " + accessToken)
                .build();
        return factory.newBarrageSession(sessionConfig);
    }

    public BufferAllocator allocator() {
        return allocator;
    }

    /** Opens the engine execution context on the current thread; required around subscription handling. */
    public SafeCloseable openExecutionContext() {
        return executionContext.open();
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        factory.managedChannel().shutdownNow();
        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
            factory.managedChannel().awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
