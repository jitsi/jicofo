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
package org.jitsi.jicofo.rest;

import net.java.sip.communicator.util.Logger;
import org.jitsi.jicofo.*;
import org.jitsi.jicofo.reservation.*;

/**
 * Implements {@link ReservationSystem} in order to integrate with REST API of
 * the reservation system.<br/> Creates/destroys conferences via API endpoint
 * and also enforces scheduled conference duration.
 *
 * @author Pawel Domas
 */
public class RESTControl
    implements FocusManager.FocusAllocationListener
{
    /**
     * The logger.
     */
    private final static Logger logger
        = Logger.getLogger(org.jitsi.impl.reservation.rest.RESTReservations.class);

    private final RequestHandler requestHandler;

    /**
     * Focus manager instance.
     */
    private FocusManager focusManager;

    /**
     *
     */
    public RESTControl()
    {
        this.requestHandler = new RequestHandler();
    }

    /**
     * Initializes this instance and starts background tasks required by
     * <tt>RESTReservations</tt> to work properly.
     *
     * @param focusManager <tt>FocusManager</tt> instance that manages
     *                     conference pool.
     */
    public void start(FocusManager focusManager)
    {
        if (this.focusManager != null)
        {
            throw new IllegalStateException("already started");
        }

        if (focusManager == null)
        {
            throw new NullPointerException("focusManager");
        }

        this.focusManager = focusManager;
        focusManager.setFocusAllocationListener(this);
    }

    /**
     * Stops this instance and all threads created by it.
     */
    public void stop()
    {
        if (focusManager != null)
        {
            focusManager.setFocusAllocationListener(null);
            focusManager = null;
        }
    }

    /**
     * Implements in order to listen for ended conferences and remove them from
     * the reservation system.
     *
     * {@inheritDoc}
     */
    @Override
    synchronized public void onFocusDestroyed(String roomName)
    {

    }
}
