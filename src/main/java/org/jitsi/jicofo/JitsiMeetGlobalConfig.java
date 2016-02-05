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
     * The name of configuration property that sets {@link #maxSSRCsPerUser}.
     */
    private final static String MAX_SSRC_PER_USER_CONFIG_PNAME
        = "org.jitsi.jicofo.MAX_SSRC_PER_USER";

    /**
     * The default value for {@link #maxSSRCsPerUser}.
     */
    private final static int DEFAULT_MAX_SSRC_PER_USER = 20;

    /**
     * Maximal amount of SSRCs per media that can be advertised by
     * conference participant.
     */
    private int maxSSRCsPerUser;

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
        this.maxSSRCsPerUser
            = configService.getInt(
                MAX_SSRC_PER_USER_CONFIG_PNAME, DEFAULT_MAX_SSRC_PER_USER);
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
}
