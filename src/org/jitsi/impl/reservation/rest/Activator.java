/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
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
