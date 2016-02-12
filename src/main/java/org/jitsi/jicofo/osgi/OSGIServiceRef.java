package org.jitsi.jicofo.osgi;

import com.sun.istack.internal.*;
import net.java.sip.communicator.util.*;
import org.osgi.framework.*;

/**
 * Created by pdomas on 08/02/16.
 */
public class OSGIServiceRef<ServiceClass>
{
    private final BundleContext ctx;

    private final Class<ServiceClass> clazz;

    private ServiceClass instance;

    public OSGIServiceRef(BundleContext ctx, Class<ServiceClass> serviceClass)
    {
        this.ctx = ctx;
        this.clazz = serviceClass;
    }

    @Nullable
    public ServiceClass get()
    {
        if (instance == null)
        {
            instance = ServiceUtils.getService(ctx, clazz);
        }
        return instance;
    }
}
