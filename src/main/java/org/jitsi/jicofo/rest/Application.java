package org.jitsi.jicofo.rest;

import org.glassfish.jersey.server.*;
import org.jitsi.jicofo.util.*;
import org.osgi.framework.*;

public class Application extends ResourceConfig
{
    public Application(BundleContext bundleContext)
    {
        register(new OsgiServiceBinder(bundleContext));
        packages("org.jitsi.jicofo.rest");
    }
}
