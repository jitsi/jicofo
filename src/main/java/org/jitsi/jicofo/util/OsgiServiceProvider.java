package org.jitsi.jicofo.util;

import org.jitsi.osgi.*;
import org.osgi.framework.*;

public class OsgiServiceProvider<T>
{
    protected final BundleContext bundleContext;
    protected final Class<T> typeClass;

    public OsgiServiceProvider(BundleContext bundleContext, Class<T> typeClass)
    {
        this.bundleContext = bundleContext;
        this.typeClass = typeClass;
    }

    public T get()
    {
        return ServiceUtils2.getService(bundleContext, typeClass);
    }
}
