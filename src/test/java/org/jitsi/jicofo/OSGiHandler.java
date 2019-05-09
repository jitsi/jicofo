/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2018 - present 8x8, Inc.
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
import net.java.sip.communicator.impl.configuration.*;
import org.jitsi.jicofo.osgi.*;
import org.jitsi.meet.*;
import org.osgi.framework.*;

/**
 * Helper class takes encapsulates OSGi specifics operations.
 *
 * FIXME there is similar class in JVB
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

    private MockMainMethodActivator mockMain;

    private static OSGiHandler instance = new OSGiHandler();

    private boolean deadlocked;

    private OSGiHandler() { }

    public static OSGiHandler getInstance()
    {
        return instance;
    }

    public void setDeadlocked(boolean deadlocked)
    {
        this.deadlocked = deadlocked;
        if (deadlocked)        {

            ((FailureAwareBundleContext)bc)
                .setFailureMessage("OSGi stack is blocked by a deadlock");
        }
        else
        {
            ((FailureAwareBundleContext)bc).setFailureMessage(null);
        }
    }

    public void init()
        throws Exception
    {
        if (deadlocked)
            throw new RuntimeException("Running on deadlocked stack");

        System.setProperty("org.jitsi.jicofo.PING_INTERVAL", "0");
        System.setProperty(FocusManager.HOSTNAME_PNAME, "test.domain.net");
        System.setProperty(FocusManager.XMPP_DOMAIN_PNAME, "test.domain.net");
        System.setProperty(FocusManager.FOCUS_USER_DOMAIN_PNAME, "focusdomain");
        System.setProperty(FocusManager.FOCUS_USER_NAME_PNAME, "focus");
        System.setProperty(ConfigurationActivator.PNAME_USE_PROPFILE_CONFIG,
                "true");

        this.bundleActivator = new BundleActivator()
        {
            @Override
            public void start(BundleContext bundleContext)
                throws Exception
            {
                bc = new FailureAwareBundleContext(bundleContext);
                synchronized (syncRoot)
                {
                    syncRoot.notifyAll();
                }
            }

            @Override
            public void stop(BundleContext bundleContext)
                throws Exception
            {
                bc = null;
                synchronized (syncRoot)
                {
                    syncRoot.notifyAll();
                }
            }
        };

        JicofoBundleConfig jicofoBundles = new JicofoBundleConfig();
        jicofoBundles.setUseMockProtocols(true);
        OSGi.setBundleConfig(jicofoBundles);
        OSGi.setClassLoader(ClassLoader.getSystemClassLoader());

        OSGi.start(bundleActivator);

        mockMain = new MockMainMethodActivator();

        OSGi.start(mockMain);

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
        throws Exception
    {
        if (deadlocked)
            return;

        if (bc != null)
        {
            if (mockMain != null)
            {
                OSGi.stop(mockMain);
            }

            OSGi.stop(bundleActivator);
        }

        if (bc != null)
            throw new RuntimeException("Failed to stop OSGI");
    }

    public BundleContext bc()
    {
        return bc;
    }

    public boolean isDeadlocked()
    {
        return deadlocked;
    }
}
