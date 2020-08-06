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
import org.jitsi.jicofo.codec.*;
import org.jitsi.service.configuration.*;
import org.jitsi.osgi.*;

import org.jitsi.utils.concurrent.*;
import org.jitsi.utils.logging.*;
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
     * The default size for the shared thread pool used by Jicofo.
     */
    private static final int DEFAULT_SHARED_POOL_SIZE = 1500;

    /**
     * The name of the config property that can be used to change the Jicofo
     * thread pool size.
     */
    private static final String SHARED_POOL_SIZE_CFG_PROP
        = "org.jitsi.jicofo.SHARED_POOL_SIZE";

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
     * {@link ConfigurationService} instance cached by the activator.
     */
    private static OSGIServiceRef<ConfigurationService> configServiceRef;

    /**
     * The Jingle offer factory to use in this bundle.
     */
    private static JingleOfferFactory jingleOfferFactory;

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

    /**
     * Global configuration of Jitsi COnference FOcus
     */
    private JitsiMeetGlobalConfig globalConfig;

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

        configServiceRef
            = new OSGIServiceRef<>(context, ConfigurationService.class);

        int maxSharedPoolSize
            = configServiceRef.get()
                .getInt(SHARED_POOL_SIZE_CFG_PROP, DEFAULT_SHARED_POOL_SIZE);

        logger.info("Max shared pool size: " + maxSharedPoolSize);

        sharedPool
            = new ThreadPoolExecutor(
                0, maxSharedPoolSize,
                60L, TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                new CustomizableThreadFactory("Jicofo Cached", true));

        jingleOfferFactory = new JingleOfferFactory();

        this.scheduledPoolRegistration = context.registerService(
                ScheduledExecutorService.class, scheduledPool, null);

        globalConfig = JitsiMeetGlobalConfig.startGlobalConfigService(context);

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

        configServiceRef = null;
        eventAdminRef = null;

        if (globalConfig != null)
        {
            globalConfig.stopGlobalConfigService();
            globalConfig = null;
        }
    }

    /**
     * Returns the instance of <tt>ConfigurationService</tt>.
     */
    public static ConfigurationService getConfigService()
    {
        return configServiceRef.get();
    }

    /**
     * Gets the Jingle offer factory to use in this bundle.
     *
     * @return the Jingle offer factory to use in this bundle
     */
    public static JingleOfferFactory getJingleOfferFactory()
    {
        return jingleOfferFactory;
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
