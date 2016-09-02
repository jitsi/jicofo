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

import net.java.sip.communicator.util.*;
import org.jitsi.service.configuration.*;
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
    private final static Logger logger
        = Logger.getLogger(JitsiMeetGlobalConfig.class);

    /**
     * The name of configuration property that disable auto owner role granting.
     */
    private final static String DISABLE_AUTO_OWNER_PNAME
        = "org.jitsi.jicofo.DISABLE_AUTO_OWNER";

    /**
     * The name of configuration property that sets {@link #maxSSRCsPerUser}.
     */
    private final static String MAX_SSRC_PER_USER_CONFIG_PNAME
        = "org.jitsi.jicofo.MAX_SSRC_PER_USER";

    /**
     * The name of configuration property that sets
     * {@link #singleParticipantTimeout}.
     */
    private final static String SINGLE_PARTICIPANT_TIMEOUT_CONFIG_PNAME
        = "org.jitsi.jicofo.SINGLE_PARTICIPANT_TIMEOUT";

    /**
     * The default value for {@link #maxSSRCsPerUser}.
     */
    private final static int DEFAULT_MAX_SSRC_PER_USER = 20;

    /**
     * The default value for {@link #singleParticipantTimeout}.
     */
    private final static long DEFAULT_SINGLE_PARTICIPANT_TIMEOUT = 20000;

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
     * Flag indicates whether auto owner feature is active. First participant to
     * join the room will become conference owner. When the owner leaves the
     * room next participant be selected as new owner.
     */
    private boolean autoOwner = true;

    /**
     * Tells how many seconds we're going to wait for the Jibri to start
     * recording. If set to <tt>-1</tt> it means that these timeouts are
     * disabled in the current session.
     */
    private int jibriPendingTimeout;

    /**
     * Maximal amount of SSRCs per media that can be advertised by
     * conference participant.
     */
    private int maxSSRCsPerUser;

    /**
     * Tells how long participant's media session will be kept alive once it
     * remains the only person in the room - which means that nobody is
     * receiving his/her media. This participant could be timed out immediately
     * as well, but we don't want to reallocate channels when the other peer is
     * only reloading his/her page. The value is amount of time measured in
     * milliseconds.
     */
    private long singleParticipantTimeout;

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
            = ServiceUtils.getService(ctx, ConfigurationService.class);

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
        return ServiceUtils.getService(bc, JitsiMeetGlobalConfig.class);
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
        if (FocusBundleActivator.getConfigService()
                .getBoolean(DISABLE_AUTO_OWNER_PNAME, false))
        {
            autoOwner = false;
        }

        logger.info("Automatically grant 'owner' role: " + autoOwner);

        this.maxSSRCsPerUser
            = configService.getInt(
                    MAX_SSRC_PER_USER_CONFIG_PNAME, DEFAULT_MAX_SSRC_PER_USER);

        jibriPendingTimeout
            = configService.getInt(
                    JIBRI_PENDING_TIMEOUT_PROP_NAME,
                    JIBRI_DEFAULT_PENDING_TIMEOUT);

        if (jibriPendingTimeout > 0)
        {
            logger.info(
                    "Jibri requests in PENDING state will be timed out after: "
                        + jibriPendingTimeout + " seconds");
        }
        else
        {
            logger.warn("Jibri PENDING timeouts are disabled");
        }

        singleParticipantTimeout
            = configService.getLong(
                    SINGLE_PARTICIPANT_TIMEOUT_CONFIG_PNAME,
                    DEFAULT_SINGLE_PARTICIPANT_TIMEOUT);

        logger.info(
                "Lonely participants will be \"terminated\" after "
                    + singleParticipantTimeout +" milliseconds");
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
    public int getMaxSSRCsPerUser()
    {
        return maxSSRCsPerUser;
    }

    /**
     * Gets the value for "single participant timeout".
     * @return the value in milliseconds.
     * @see #singleParticipantTimeout
     */
    public long getSingleParticipantTimeout()
    {
        return singleParticipantTimeout;
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
     * Indicates whether auto owner feature is active. First participant to join
     * the room will become conference owner. When the owner leaves the room
     * next participant will be selected as the new owner.
     *
     * @return <tt>true</tt> if the auto-owner feature is enabled or
     * <tt>false</tt> otherwise.
     */
    public boolean isAutoOwnerEnabled()
    {
        return this.autoOwner;
    }
}
