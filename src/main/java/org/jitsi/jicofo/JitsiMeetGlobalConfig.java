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
     * The name of configuration property that sets {@link #maxSourcesPerUser}.
     */
    private final static String MAX_SSRC_PER_USER_CONFIG_PNAME = "org.jitsi.jicofo.MAX_SSRC_PER_USER";

    /**
     * The default value for {@link #maxSourcesPerUser}.
     */
    private final static int DEFAULT_MAX_SSRC_PER_USER = 20;

    /**
     * The name of the config property which specifies how long we're going to
     * wait for Jibri to start recording from the time it accepted START request
     */
    private static final String JIBRI_PENDING_TIMEOUT_PROP_NAME
        = "org.jitsi.jicofo.jibri.PENDING_TIMEOUT";

    /**
     * The default value for {@link #JIBRI_PENDING_TIMEOUT_PROP_NAME}.
     */
    private static final int JIBRI_DEFAULT_PENDING_TIMEOUT = 90;

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
     * Tells how many seconds we're going to wait for the Jibri to start
     * recording. If set to <tt>-1</tt> it means that these timeouts are
     * disabled in the current session.
     */
    private int jibriPendingTimeout;

    /**
     * How many attempts we'll make to retry a given Jibri request if the Jibri
     * fails.
     */
    private int numJibriRetries;

    /**
     * Maximal amount of sources per media that can be advertised by
     * conference participant.
     */
    private int maxSourcesPerUser;

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
        this.maxSourcesPerUser
            = configService.getInt(MAX_SSRC_PER_USER_CONFIG_PNAME, DEFAULT_MAX_SSRC_PER_USER);

        jibriPendingTimeout
            = configService.getInt(
                    JIBRI_PENDING_TIMEOUT_PROP_NAME,
                    JIBRI_DEFAULT_PENDING_TIMEOUT);

        if (jibriPendingTimeout > 0)
        {
            logger.info("Jibri requests in PENDING state will be timed out after: " + jibriPendingTimeout + " seconds");
        }
        else
        {
            logger.warn("Jibri PENDING timeouts are disabled");
        }

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
     * Returns maximal amount of SSRCs per media that can be advertised by
     * conference participant.
     *
     * @return <tt>int</tt> value - see above.
     */
    public int getMaxSourcesPerUser()
    {
        return maxSourcesPerUser;
    }

    /**
     * Tells how many seconds we're going to wait for the Jibri to start
     * recording. If set to <tt>-1</tt> it means that these timeouts are
     * disabled in the current session.
     *
     * @return <tt>int</tt> which is the number of seconds we wait for the Jibri
     *         to start recording after it accepted our request.
     */
    public int getJibriPendingTimeout()
    {
        return jibriPendingTimeout;
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
