package io.deephaven.oidc.entra.authz;

import io.deephaven.configuration.Configuration;
import io.deephaven.server.runner.MainHelper;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

/**
 * Entry point for the direct-Entra Deephaven server with role-based authorization. The Docker
 * image swaps this in for {@code io.deephaven.server.jetty.JettyMain} in the launch script.
 */
public final class EntraServerMain {
    public static void main(String[] args)
            throws IOException, InterruptedException, ClassNotFoundException, TimeoutException {
        final Configuration configuration = MainHelper.init(args, EntraServerMain.class);
        new EntraServerComponentFactory()
                .build(configuration)
                .getServer()
                .run()
                .join();
    }
}
