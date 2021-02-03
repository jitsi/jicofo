/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2015-Present 8x8, Inc.
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

import org.jitsi.impl.protocol.xmpp.*;
import org.jitsi.jicofo.xmpp.*;
import org.jitsi.protocol.xmpp.*;

import org.jitsi.utils.logging2.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * Class takes care of creating and removing temporary XMPP account while
 * exposing protocol provider service to end user.
 *
 * @author Pawel Domas
 */
public class ProtocolProviderHandler
    implements RegistrationListener
{
    // TODO: Remove the empty context when jitsi-utils is updated
    private final static Logger logger
            = new LoggerImpl(ProtocolProviderHandler.class.getName(), new LogContext(Collections.emptyMap()));

    /**
     * XMPP protocol provider service used by the focus.
     */
    private XmppProvider protocolService;

    /**
     * Registration listeners notified about encapsulated protocol service
     * instance registration state changes.
     */
    private final List<RegistrationListener> regListeners = new CopyOnWriteArrayList<>();

    private final List<XmppConnectionListener> xmppConnectionListeners = new ArrayList<>();

    private final XmppConnectionConfig config;

    /**
     * Executor to use to run `register`.
     */
    private final ScheduledExecutorService scheduledExecutorService;

    public ProtocolProviderHandler(XmppConnectionConfig config, ScheduledExecutorService scheduledExecutorService)
    {
        this.config = config;
        this.scheduledExecutorService = scheduledExecutorService;
        logger.addContext("xmpp_connection", config.getName());
    }

    public void start(XmppProviderFactory xmppProviderFactory)
    {
        protocolService = xmppProviderFactory.createXmppProvider(config, logger);
        protocolService.addRegistrationListener(this);
        if (protocolService instanceof XmppProtocolProvider && config.getDisableCertificateVerification())
        {
            ((XmppProtocolProvider) protocolService).setDisableCertificateVerification(true);
        }
    }

    /**
     * Stops this instance and removes temporary XMPP account.
     */
    public void stop()
    {
        protocolService.removeRegistrationListener(this);
        protocolService.unregister();
    }

    /**
     * Passes registration state changes of encapsulated protocol provider to
     * registered {@link #regListeners}.
     *
     * {@inheritDoc}
     */
    @Override
    public void registrationChanged(boolean registered)
    {
        logger.info(registered ? "registered" : "unregistered");

        if (registered)
        {
            XmppConnection xmppConnection = protocolService.getXmppConnection();
            if (xmppConnection != null)
            {
                xmppConnection.setReplyTimeout(config.getReplyTimeout().toMillis());
                xmppConnectionListeners.forEach(it -> it.xmppConnectionInitialized(xmppConnection));
                logger.info("Set replyTimeout=" + config.getReplyTimeout());
            }
            else
            {
                logger.error("Unable to set Smack replyTimeout, no XmppConnection.");
            }
        }

        for(RegistrationListener l : regListeners)
        {
            try
            {
                l.registrationChanged(registered);
            }
            catch (Exception e)
            {
                logger.error(e.getMessage(), e);
            }
        }
    }

    /**
     * Adds given listener to the list of registration state change listeners
     * notified about underlying protocol provider registration state changes.
     * @param l the listener that will be notified about created protocol
     *           provider's registration state changes.
     */
    public void addRegistrationListener(RegistrationListener l)
    {
        regListeners.add(l);
    }

    /**
     * Removes given <tt>RegistrationStateChangeListener</tt>.
     */
    public void removeRegistrationListener(RegistrationListener l)
    {
        boolean ok = regListeners.remove(l);
    }

    /**
     * Returns <tt>true</tt> if underlying protocol provider service has
     * registered.
     */
    public boolean isRegistered()
    {
        return protocolService.isRegistered();
    }

    /**
     * Starts registration process of underlying protocol provider service.
     */
    public void register()
    {
        protocolService.register(scheduledExecutorService);
    }

    /**
     * Returns underlying protocol provider service instance if this
     * <tt>ProtocolProviderHandler</tt> has been started or <tt>null</tt>
     * otherwise.
     */
    public XmppProvider getProtocolProvider()
    {
        return protocolService;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString()
    {
        return "ProtocolProviderHandler " + config;
    }

    public void addXmppConnectionListener(XmppConnectionListener listener)
    {
        xmppConnectionListeners.add(listener);

        XmppConnection connection = protocolService.getXmppConnection();
        if (connection != null)
        {
            listener.xmppConnectionInitialized(connection);
        }
    }

    /**
     * Interface to use to notify about the XmppConnection being initialized. This is just meant as a temporary solution
     * until the flow to setup XMPP is cleaned up.
     */
    public interface XmppConnectionListener
    {
        void xmppConnectionInitialized(XmppConnection xmppConnection);
    }

}
