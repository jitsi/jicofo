package org.jitsi.jicofo.util;

import org.glassfish.hk2.utilities.binding.*;
import org.jitsi.health.*;
import org.jitsi.osgi.*;
import org.osgi.framework.*;

public class OsgiServiceBinder extends AbstractBinder
{
    protected final BundleContext bundleContext;

    public OsgiServiceBinder(BundleContext bundleContext)
    {
        this.bundleContext = bundleContext;
    }

    @Override
    protected void configure()
    {
        bind(new FocusManagerProvider(bundleContext)).to(FocusManagerProvider.class);
        bind(new JibriStatsProvider(bundleContext)).to(JibriStatsProvider.class);
        bind(new VersionServiceProvider(bundleContext)).to(VersionServiceProvider.class);
        bind(new HealthCheckServiceProvider(bundleContext)).to(HealthCheckServiceProvider.class);
    }
}
