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

import net.java.sip.communicator.service.protocol.*;
import net.java.sip.communicator.util.*;
import org.jitsi.impl.protocol.xmpp.*;
import org.jitsi.jicofo.*;
import org.jitsi.jicofo.reservation.*;
import org.jitsi.jicofo.xmpp.*;

import java.io.*;
import java.util.*;

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

    /**
     * Focus manager instance.
     */
    private FocusManager focusManager;

    private Properties properties;

    /**
     *
     */
    public RESTControl()
    {
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

        loadProperties();

        this.focusManager = focusManager;
        focusManager.setFocusAllocationListener(this);
    }

    private void loadProperties()
    {
        this.properties = new Properties();
        FileInputStream fin = null;
        try
        {
            fin = new FileInputStream("redshorts.properties");
            properties.load(fin);
        }
        catch (IOException e)
        {
            logger.error("ERROR", e);
            // we want to die
            throw new RuntimeException();
        }
        finally
        {
            if (fin != null)
            {
                try
                {
                    fin.close();
                }
                catch (IOException e)
                {
                    logger.error("ERROR ON CLOSE", e);
                }
            }
        }
    }

    public String getRoomJid(String guid)
    {
        return properties.getProperty(guid);
    }

    public String getGuid(String room)
    {
        if (room == null)
            throw new NullPointerException("room");

        for (String guid : properties.stringPropertyNames())
        {
            if (room.equals(properties.getProperty(guid)))
                return guid;
        }

        return null;
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

    private XmppProtocolProvider protocolProvider;

    private XmppProtocolProvider getProtocolProvider()
    {
        if (protocolProvider == null)
        {
            protocolProvider
                = (XmppProtocolProvider) ServiceUtils.getService(
                        Activator.bundleContext,
                        ProtocolProviderService.class);
        }
        return protocolProvider;

    }

    private FocusComponent focusComponent;

    FocusComponent getFocusComponent()
    {
        if (focusComponent == null)
        {
            focusComponent = ServiceUtils.getService(
                Activator.bundleContext,
                FocusComponent.class);
        }
        return focusComponent;
    }

    public String sendMessage(String roomJid, String msg)
    {
        XmppProtocolProvider xmpp = getProtocolProvider();
        if (xmpp == null)
            return "Internal error: no XMPP protocol provider";

        return xmpp.sendMessage(roomJid, msg);

        // FIXME use the code below if you need to send from component
        /*FocusComponent focusComponent = getFocusComponent();
        if (focusComponent == null)
            return "Internal error: no focus component";

        return focusComponent.sendMessage(roomJid, msg);*/
    }
}
