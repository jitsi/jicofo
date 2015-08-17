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
package org.jitsi.impl.reservation.rest;

import net.java.sip.communicator.util.*;
import net.java.sip.communicator.util.Logger;
import org.jitsi.jicofo.*;
import org.jitsi.jicofo.reservation.*;
import org.jitsi.service.configuration.*;
import org.jitsi.util.*;
import org.osgi.framework.*;

/**
 * Plugin bundle activator for REST reservation system.
 *
 * @author Pawel Domas
 */
public class Activator
    implements BundleActivator
{
    /**
     * The logger.
     */
    private final static Logger logger
        = Logger.getLogger(Activator.class);

    /**
     * Reservation system handler.
     */
    RESTReservations restReservations;

    /**
     * Reservation system OSGi service registration.
     */
    private ServiceRegistration<ReservationSystem> serviceRegistration;

    @Override
    public void start(BundleContext context)
            throws Exception
    {
        ConfigurationService config
            = ServiceUtils.getService(context, ConfigurationService.class);
        String apiBaseUrl
            = config.getString(RESTReservations.API_BASE_URL_PNAME);

        if (StringUtils.isNullOrEmpty(apiBaseUrl))
            return;

        logger.info("REST reservation API will use base URL: " + apiBaseUrl);

        restReservations = new RESTReservations(apiBaseUrl);

        serviceRegistration = context.registerService(
            ReservationSystem.class, restReservations, null);

        FocusManager focusManager
            = ServiceUtils.getService(context, FocusManager.class);

        restReservations.start(focusManager);
    }

    @Override
    public void stop(BundleContext context)
            throws Exception
    {
        if (serviceRegistration != null)
        {
            serviceRegistration.unregister();
            serviceRegistration = null;

            restReservations.stop();
        }
    }
}
