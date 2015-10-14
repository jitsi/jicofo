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

import net.java.sip.communicator.impl.protocol.jabber.extensions.caps.*;
import net.java.sip.communicator.util.*;

import org.jitsi.service.configuration.*;

import org.jitsi.eventadmin.*;
import org.jitsi.osgi.*;
import org.osgi.framework.*;

import java.util.concurrent.*;

/**
 * Activator of the Jitsi Meet Focus bundle.
 *
 * @author Pawel Domas
 */
public class FocusBundleActivator
    implements BundleActivator
{
    /**
     * The number of threads available in the thread pool shared through OSGi.
     */
    private static final int SHARED_POOL_SIZE = 20;

    /**
     * OSGi bundle context held by this activator.
     */
    public static BundleContext bundleContext;

    /**
     * {@link ConfigurationService} instance cached by the activator.
     */
    private static ConfigurationService configService;

    /**
     * {@link org.jitsi.jicofo.FocusManager} instance created by this activator.
     */
    private FocusManager focusManager;

    /**
     * Shared thread pool available through OSGi for other components that do
     * not like to manage their own pool.
     */
    private static ScheduledExecutorService sharedThreadPool;

    @Override
    public void start(BundleContext context)
        throws Exception
    {
        bundleContext = context;

        EntityCapsManager.setBundleContext(context);

        sharedThreadPool = Executors.newScheduledThreadPool(SHARED_POOL_SIZE);

        context.registerService(
            ExecutorService.class, sharedThreadPool, null);
        context.registerService(
            ScheduledExecutorService.class, sharedThreadPool, null);

        focusManager = new FocusManager();
        context.registerService(FocusManager.class, focusManager, null);
    }

    @Override
    public void stop(BundleContext context)
        throws Exception
    {
        sharedThreadPool.shutdownNow();
        sharedThreadPool = null;

        configService = null;

        EntityCapsManager.setBundleContext(null);
    }

    /**
     * Returns the instance of <tt>ConfigurationService</tt>.
     */
    public static ConfigurationService getConfigService()
    {
        if (configService == null)
        {
            configService = ServiceUtils.getService(
                bundleContext, ConfigurationService.class);
        }
        return configService;
    }

    /**
     * Returns the <tt>EventAdmin</tt> instance, if any.
     * @return the <tt>EventAdmin</tt> instance, if any.
     */
    public static EventAdmin getEventAdmin()
    {
        if (bundleContext != null)
        {
            return ServiceUtils2.getService(bundleContext,
                                            EventAdmin.class);
        }
        return null;
    }

    /**
     * Returns shared thread pool service.
     */
    public static ScheduledExecutorService getSharedThreadPool()
    {
        return sharedThreadPool;
    }
}
