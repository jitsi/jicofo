/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2015 Atlassian Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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
