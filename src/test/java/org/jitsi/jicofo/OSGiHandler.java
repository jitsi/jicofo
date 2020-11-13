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

import org.jitsi.config.JitsiConfig;
import org.jitsi.jicofo.osgi.*;
import org.jitsi.jicofo.xmpp.FocusComponent;
import org.jitsi.jicofo.xmpp.XmppClientConnectionConfig;
import org.jitsi.meet.*;
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
    public FailureAwareBundleContext bc;

    private BundleActivator bundleActivator;

    private final Object syncRoot = new Object();

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
        if (deadlocked)
        {
            bc.setFailureMessage("OSGi stack is blocked by a deadlock");
        }
        else
        {
            bc.setFailureMessage(null);
        }
    }

    public void init()
        throws Exception
    {
        if (deadlocked)
            throw new RuntimeException("Running on deadlocked stack");

        FocusComponent.suppressConnect = true;
        System.setProperty("org.jitsi.jicofo.PING_INTERVAL", "0");
        // TODO replace with withLegacyConfig
        System.setProperty(XmppClientConnectionConfig.legacyXmppDomainPropertyName, "test.domain.net");
        System.setProperty(XmppClientConnectionConfig.legacyDomainPropertyName, "test.domain.net");
        System.setProperty(XmppClientConnectionConfig.legacyUsernamePropertyName, "focus");
        JitsiConfig.Companion.reloadNewConfig();

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

        if (bc == null)
        {
            synchronized (syncRoot)
            {
                syncRoot.wait(5000);
            }
        }

        if (bc == null)
        {
            throw new RuntimeException("Failed to start OSGI");
        }

        // Activators are executed asynchronously, so a hack to wait for the last activator is used
        WaitableBundleActivator.waitUntilStarted();
    }

    public void shutdown()
        throws Exception
    {
        if (deadlocked)
            return;

        if (bc != null)
        {
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
