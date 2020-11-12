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

import net.java.sip.communicator.service.protocol.*;
import net.java.sip.communicator.service.protocol.event.*;

import org.jitsi.impl.protocol.xmpp.XmppProtocolProvider;
import org.jitsi.jicofo.util.*;
import org.jitsi.jicofo.xmpp.XmppConnectionConfig;
import org.jitsi.protocol.xmpp.*;

import org.jitsi.utils.logging.*;
import org.osgi.framework.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * Class takes care of creating and removing temporary XMPP account while
 * exposing protocol provider service to end user.
 *
 * @author Pawel Domas
 */
public class ProtocolProviderHandler
    implements RegistrationStateChangeListener
{
    private final static Logger logger = Logger.getLogger(ProtocolProviderHandler.class);

    /**
     * XMPP provider factory used to create and destroy XMPP account used by
     * the focus.
     */
    private ProtocolProviderFactory xmppProviderFactory;

    /**
     * XMPP account used by the focus.
     */
    private AccountID xmppAccount;

    /**
     * XMPP protocol provider service used by the focus.
     */
    private ProtocolProviderService protocolService;

    /**
     * Registration listeners notified about encapsulated protocol service
     * instance registration state changes.
     */
    private final List<RegistrationStateChangeListener> regListeners = new CopyOnWriteArrayList<>();

    private final XmppConnectionConfig config;

    public ProtocolProviderHandler(XmppConnectionConfig config)
    {
        this.config = config;
    }

    public void start()
    {
        xmppProviderFactory
            = ProtocolProviderFactory.getProtocolProviderFactory(
                    FocusBundleActivator.bundleContext,
                    ProtocolNames.JABBER);

        if (config.getPassword() != null)
        {
            xmppAccount
                = xmppProviderFactory.createAccount(
                FocusAccountFactory.createFocusAccountProperties(
                    config.getHostname(),
                    String.valueOf(config.getPort()),
                    config.getDomain(),
                    config.getUsername(),
                    config.getPassword()));
        }
        else
        {
            xmppAccount
                = xmppProviderFactory.createAccount(
                FocusAccountFactory.createFocusAccountProperties(
                    config.getHostname(),
                    String.valueOf(config.getPort()),
                    config.getDomain(),
                    config.getUsername()));
        }

        if (!xmppProviderFactory.loadAccount(xmppAccount))
        {
            throw new RuntimeException("Failed to load account: " + xmppAccount);
        }

        ServiceReference<ProtocolProviderService> protoRef = xmppProviderFactory.getProviderForAccount(xmppAccount);

        protocolService = FocusBundleActivator.bundleContext.getService(protoRef);
        protocolService.addRegistrationStateChangeListener(this);
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
        protocolService.removeRegistrationStateChangeListener(this);

        xmppProviderFactory.uninstallAccount(xmppAccount);
    }

    /**
     * Passes registration state changes of encapsulated protocol provider to
     * registered {@link #regListeners}.
     *
     * {@inheritDoc}
     */
    @Override
    public void registrationStateChanged(RegistrationStateChangeEvent evt)
    {
        logger.info(this + ": " + evt);

        if (RegistrationState.REGISTERED.equals(evt.getNewState()))
        {
            OperationSetDirectSmackXmpp operationSetDirectSmackXmpp
                = protocolService.getOperationSet(OperationSetDirectSmackXmpp.class);
            if (operationSetDirectSmackXmpp != null)
            {
                operationSetDirectSmackXmpp.getXmppConnection().setReplyTimeout(config.getReplyTimeout().toMillis());
                logger.info("Set replyTimeout=" + config.getReplyTimeout());
            }
            else
            {
                logger.error("Unable to set Smack replyTimeout, no OperationSet.");
            }
        }

        for(RegistrationStateChangeListener l : regListeners)
        {
            try
            {
                l.registrationStateChanged(evt);
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
    public void addRegistrationListener(RegistrationStateChangeListener l)
    {
        regListeners.add(l);
    }

    /**
     * Removes given <tt>RegistrationStateChangeListener</tt>.
     */
    public void removeRegistrationListener(RegistrationStateChangeListener l)
    {
        boolean ok = regListeners.remove(l);
        logger.debug("Listener removed ? " + ok + ", " + l);
    }

    /**
     * Utility method for obtaining operation sets from underlying protocol
     * provider service.
     */
    public <T extends OperationSet> T getOperationSet(Class<T> opSetClass)
    {
        return protocolService.getOperationSet(opSetClass);
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
        // FIXME: not pooled thread created
        new RegisterThread(protocolService).start();
    }

    /**
     * Returns underlying protocol provider service instance if this
     * <tt>ProtocolProviderHandler</tt> has been started or <tt>null</tt>
     * otherwise.
     */
    public ProtocolProviderService getProtocolProvider()
    {
        return protocolService;
    }

    /**
     * Obtains XMPP connection for the underlying XMPP protocol provider
     * service.
     * @return {@link XmppConnection} or null if the underlying protocol provider is not registered yet.
     */
    public XmppConnection getXmppConnection()
    {
        return Objects.requireNonNull(
                getOperationSet(OperationSetDirectSmackXmpp.class), "OperationSetDirectSmackXmpp")
                    .getXmppConnection();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString()
    {
        return protocolService != null ? protocolService.toString() : super.toString();
    }
}
