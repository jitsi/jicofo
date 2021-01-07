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

import org.jitsi.utils.concurrent.*;
import org.jitsi.utils.logging.*;
import org.osgi.framework.*;

import java.util.concurrent.*;

import static org.jitsi.jicofo.util.ServiceUtilsKt.getService;

/**
 * Activator of the Jitsi Meet Focus bundle.
 *
 * @author Pawel Domas
 */
public class FocusBundleActivator
    implements BundleActivator
{
    /**
     * The number of threads available in the scheduled executor pool shared
     * through OSGi.
     */
    private static final int SHARED_SCHEDULED_POOL_SIZE = 200;

    /**
     * OSGi bundle context held by this activator.
     */
    public static BundleContext bundleContext;

    /**
     * Shared thread pool available through OSGi for other components that do
     * not like to manage their own pool.
     */
    private ScheduledExecutorService scheduledPool;

    /**
     * {@link ServiceRegistration} for {@link #scheduledPool}.
     */
    private ServiceRegistration<ScheduledExecutorService> scheduledPoolRegistration;

    /**
     * {@link org.jitsi.jicofo.FocusManager} instance created by this activator.
     */
    private FocusManager focusManager;

    @Override
    public void start(BundleContext context)
        throws Exception
    {
        bundleContext = context;

        // Make threads daemon, so that they won't prevent from doing shutdown
        scheduledPool
            = Executors.newScheduledThreadPool(
                SHARED_SCHEDULED_POOL_SIZE,
                new CustomizableThreadFactory("Jicofo Scheduled", true));

        this.scheduledPoolRegistration = context.registerService(ScheduledExecutorService.class, scheduledPool, null);

        focusManager = new FocusManager();
        focusManager.start();
    }

    @Override
    public void stop(BundleContext context)
        throws Exception
    {
        if (focusManager != null)
        {
            focusManager.stop();
            focusManager = null;
        }

        if (scheduledPoolRegistration != null)
        {
            scheduledPoolRegistration.unregister();
            scheduledPoolRegistration = null;
        }

        if (scheduledPool != null)
        {
            scheduledPool.shutdownNow();
            scheduledPool = null;
        }
    }

    /**
     * Returns a {@link ScheduledExecutorService} shared by all components
     * through OSGi.
     */
    public static ScheduledExecutorService getSharedScheduledThreadPool()
    {
        return getService(bundleContext, ScheduledExecutorService.class);
    }
}
