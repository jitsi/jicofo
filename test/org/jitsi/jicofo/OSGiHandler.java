/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.jicofo;

import mock.*;
import org.jitsi.jicofo.osgi.*;
import org.osgi.framework.*;

/**
 * Helper class takes encapsulates OSGi specifics operations.
 *
 * @author Pawel Domas
 */
public class OSGiHandler
{
    /**
     * OSGi bundle context instance.
     */
    public BundleContext bc;

    private BundleActivator bundleActivator;

    private final Object syncRoot = new Object();

    public void init()
        throws InterruptedException
    {
        System.setProperty(FocusManager.HOSTNAME_PNAME, "testserver");
        System.setProperty(FocusManager.XMPP_DOMAIN_PNAME, "testdomain");
        System.setProperty(FocusManager.FOCUS_USER_DOMAIN_PNAME, "focusdomain");
        System.setProperty(FocusManager.FOCUS_USER_NAME_PNAME, "focus");

        this.bundleActivator = new BundleActivator()
        {
            @Override
            public void start(BundleContext bundleContext)
                throws Exception
            {
                bc = bundleContext;
                synchronized (syncRoot)
                {
                    syncRoot.notify();
                }
            }

            @Override
            public void stop(BundleContext bundleContext)
                throws Exception
            {

            }
        };

        OSGi.start(bundleActivator);

        if (bc == null)
        {
            synchronized (syncRoot)
            {
                syncRoot.wait(5000);
            }
        }

        if (bc == null)
            throw new RuntimeException("Failed to start OSGI");

        // Activators are executed asynchronously,
        // so a hack to wait for the last activator is used
        MockMainMethodActivator.waitUntilStarted(5000);
    }

    public void shutdown()
    {
        OSGi.stop();
    }


}
