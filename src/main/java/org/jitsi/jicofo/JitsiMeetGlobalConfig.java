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

import org.jitsi.osgi.*;
import org.jitsi.service.configuration.*;
import org.jitsi.utils.logging.*;
import org.osgi.framework.*;

/**
 * This class gathers config properties related to all conferences served by
 * JiCOFO.
 *
 * @author Pawel Domas
 */
public class JitsiMeetGlobalConfig
{
    /**
     * The logger instance used by this class.
     */
    private final static Logger logger = Logger.getLogger(JitsiMeetGlobalConfig.class);

    /**
     * The name of the config property which specifies how many times
     * we'll retry a given Jibri request before giving up.  Set to
     * -1 to allow infinite retries.
     */
    public static final String NUM_JIBRI_RETRIES_PNAME
            = "org.jitsi.jicofo.NUM_JIBRI_RETRIES";

    /**
     * The default value for {@link #NUM_JIBRI_RETRIES_PNAME}
     */
    private static final int DEFAULT_NUM_JIBRI_RETRIES = 5;

    /**
     * How many attempts we'll make to retry a given Jibri request if the Jibri
     * fails.
     */
    private int numJibriRetries;

    /**
     * OSGi service registration instance.
     */
    private ServiceRegistration<JitsiMeetGlobalConfig> serviceRegistration;

    /**
     * Runs <tt>JitsiMeetGlobalConfig</tt> service on given OSGi context.
     * @param ctx the OSGi context to which new service instance will be bound.
     * @return an instance of newly created and registered global config service
     */
    static JitsiMeetGlobalConfig startGlobalConfigService(BundleContext ctx)
    {
        JitsiMeetGlobalConfig config = new JitsiMeetGlobalConfig();

        config.serviceRegistration
            = ctx.registerService(JitsiMeetGlobalConfig.class, config, null);

        ConfigurationService configService
            = ServiceUtils2.getService(ctx, ConfigurationService.class);

        if (configService == null)
            throw new RuntimeException("ConfigService not found !");

        config.init(configService);

        return config;
    }

    /**
     * Obtains <tt>JitsiMeetGlobalConfig</tt> from given OSGi instance.
     *
     * @param bc the context for which we're going to obtain global config
     *           instance.
     *
     * @return <tt>JitsiMeetGlobalConfig</tt> if one is currently registered as
     *         a service in given OSGi context or <tt>null</tt> otherwise.
     */
    public static JitsiMeetGlobalConfig getGlobalConfig(BundleContext bc)
    {
        return ServiceUtils2.getService(bc, JitsiMeetGlobalConfig.class);
    }

    private JitsiMeetGlobalConfig()
    {

    }

    /**
     * Initializes this instance.
     *
     * @param configService <tt>ConfigService</tt> the configuration service
     *        which will be used to obtain values.
     */
    private void init(ConfigurationService configService)
    {
        numJibriRetries = configService.getInt(NUM_JIBRI_RETRIES_PNAME, DEFAULT_NUM_JIBRI_RETRIES);
        if (numJibriRetries >= 0)
        {
            logger.info("Will attempt a maximum of " + numJibriRetries + " Jibri retries after failure");
        }
        else
        {
            logger.info("Will retry Jibri requests infinitely " + "(if a Jibri is available)");
        }
    }

    /**
     * Unregisters this service instance from OSGi context.
     */
    void stopGlobalConfigService()
    {
        if (serviceRegistration != null)
        {
            serviceRegistration.unregister();
            serviceRegistration = null;
        }
    }

    /**
     * Tells how many retry attempts we'll make for a Jibri request when
     * a Jibri fails
     * @return the amount of retry attempts we'll make for a Jibri request when
     * a Jibri fails
     */
    public int getNumJibriRetries()
    {
        return numJibriRetries;
    }
}
