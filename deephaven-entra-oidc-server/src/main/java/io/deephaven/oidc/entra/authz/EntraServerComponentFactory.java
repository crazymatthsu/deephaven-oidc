package io.deephaven.oidc.entra.authz;

import dagger.Binds;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import io.deephaven.client.impl.BarrageSessionFactoryConfig;
import io.deephaven.configuration.Configuration;
import io.deephaven.oidc.entra.authz.EntraServerComponentFactory.EntraComponent;
import io.deephaven.server.auth.AuthorizationProvider;
import io.deephaven.server.flightsql.FlightSqlModule;
import io.deephaven.server.jetty.JettyConfig;
import io.deephaven.server.jetty.JettyServerComponent;
import io.deephaven.server.jetty.JettyServerModule;
import io.deephaven.server.runner.CommunityDefaultsModule;
import io.deephaven.server.runner.ComponentFactoryBase;
import io.deephaven.server.session.ClientChannelFactoryModule;
import io.deephaven.server.session.ClientChannelFactoryModule.UserAgent;
import io.deephaven.server.session.SslConfigModule;

import javax.inject.Singleton;
import java.io.PrintStream;
import java.util.List;

/**
 * Server assembly identical to Deephaven's out-of-the-box {@code CommunityComponentFactory},
 * except {@code CommunityAuthorizationModule} (allow-all) is replaced by a binding of
 * {@link EntraAuthorizationProvider} (role-based entitlements). Follows the official
 * customization pattern from deephaven-core's {@code server/jetty-app-custom} example.
 */
public final class EntraServerComponentFactory extends ComponentFactoryBase<EntraComponent> {

    @Override
    public EntraComponent build(Configuration configuration, PrintStream out, PrintStream err) {
        final JettyConfig jettyConfig = JettyConfig.buildFromConfig(configuration).build();
        return DaggerEntraServerComponentFactory_EntraComponent.builder()
                .withOut(out)
                .withErr(err)
                .withJettyConfig(jettyConfig)
                .build();
    }

    @Singleton
    @Component(modules = EntraModule.class)
    public interface EntraComponent extends JettyServerComponent {

        @Component.Builder
        interface Builder extends JettyServerComponent.Builder<Builder, EntraComponent> {
        }
    }

    /**
     * Replicates {@code JettyClientChannelFactoryModule} (from the unpublished
     * deephaven-server-jetty-app artifact): channel factory + SSL config + user-agent binding.
     */
    @Module(includes = {
            ClientChannelFactoryModule.class,
            SslConfigModule.class,
    })
    public interface EntraClientChannelFactoryModule {

        @Provides
        @UserAgent
        static String providesUserAgent() {
            return BarrageSessionFactoryConfig.userAgent(List.of("deephaven-entra-oidc-server"));
        }
    }

    /** Mirrors CommunityModule with the authorization provider swapped out. */
    @Module(includes = {
            JettyServerModule.class,
            FlightSqlModule.class,
            EntraClientChannelFactoryModule.class,
            CommunityDefaultsModule.class,
    })
    public interface EntraModule {

        @Binds
        AuthorizationProvider bindsAuthorizationProvider(EntraAuthorizationProvider provider);
    }
}
