package org.jitsi.jicofo.util;

import org.jitsi.jicofo.*;
import org.osgi.framework.*;

public class FocusManagerProvider extends OsgiServiceProvider<FocusManager>
{
    public FocusManagerProvider(BundleContext bundleContext) {
        super(bundleContext, FocusManager.class);
    }
}
