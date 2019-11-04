package org.jitsi.jicofo.rest;

import org.glassfish.hk2.utilities.binding.*;
import org.glassfish.jersey.server.*;
import org.jitsi.jicofo.util.*;
import org.osgi.framework.*;

import java.time.*;

public class Application extends ResourceConfig
{
    protected final Clock clock = Clock.systemUTC();

    public Application(BundleContext bundleContext)
    {
        register(new OsgiServiceBinder(bundleContext));
        register(new AbstractBinder()
        {
            @Override
            protected void configure()
            {
               bind(clock).to(Clock.class);
            }
        });
        packages("org.jitsi.jicofo.rest");
    }
}
