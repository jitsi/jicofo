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

import org.jitsi.eventadmin.*;
import org.jitsi.osgi.*;

import org.jitsi.utils.concurrent.*;
import org.jitsi.utils.logging.*;
import org.osgi.framework.*;

import java.util.concurrent.*;

import static org.jitsi.jicofo.JicofoConfig.config;

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
     * The logger.
     */
    private static final Logger logger
        = Logger.getLogger(FocusBundleActivator.class);

    /**
     * {@link EventAdmin} service reference.
     */
    private static OSGIServiceRef<EventAdmin> eventAdminRef;

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
     * A cached pool registered as {@link ExecutorService} to be shared by
     * different Jicofo components.
     */
    private static ExecutorService sharedPool;

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

        eventAdminRef = new OSGIServiceRef<>(context, EventAdmin.class);

        logger.info("Shared pool max size: " + config.getSharedPoolMaxThreads());
        sharedPool
            = new ThreadPoolExecutor(
                0, config.getSharedPoolMaxThreads(),
                60L, TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                new CustomizableThreadFactory("Jicofo Cached", true));

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

        if (sharedPool != null)
        {
            sharedPool.shutdownNow();
            sharedPool = null;
        }

        eventAdminRef = null;
    }

    /**
     * Returns the <tt>EventAdmin</tt> instance, if any.
     * @return the <tt>EventAdmin</tt> instance, if any.
     */
    public static EventAdmin getEventAdmin()
    {
        return eventAdminRef.get();
    }

    /**
     * Returns a {@link ScheduledExecutorService} shared by all components
     * through OSGi.
     */
    public static ScheduledExecutorService getSharedScheduledThreadPool()
    {
        return ServiceUtils2.getService(bundleContext, ScheduledExecutorService.class);
    }

    /**
     * Returns a cached {@link ExecutorService} shared by Jicofo components.
     */
    public static ExecutorService getSharedThreadPool()
    {
        return sharedPool;
    }
}
