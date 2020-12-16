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
package org.jitsi.jicofo.xmpp;

import org.jetbrains.annotations.*;
import org.jitsi.retry.RetryStrategy;
import org.jitsi.retry.SimpleRetryTask;
import org.jitsi.xmpp.extensions.jitsimeet.*;
import org.jitsi.jicofo.*;
import org.jitsi.jicofo.auth.*;
import org.jitsi.jicofo.reservation.*;
import org.jitsi.service.configuration.*;
import org.jitsi.utils.logging.*;
import org.jitsi.xmpp.component.*;
import org.jitsi.xmpp.util.*;

import org.jivesoftware.smack.packet.*;

import org.jivesoftware.whack.ExternalComponentManager;
import org.jxmpp.jid.*;
import org.xmpp.component.ComponentException;
import org.xmpp.packet.IQ;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.apache.commons.lang3.StringUtils.*;

/**
 * XMPP component that listens for {@link ConferenceIq}
 * and allocates {@link org.jitsi.jicofo.JitsiMeetConference}s appropriately.
 *
 * @author Pawel Domas
 */
public class FocusComponent
    extends ComponentBase
{
    /**
     * The logger.
     */
    private final static Logger logger = Logger.getLogger(FocusComponent.class);

    /**
     * (Optional)Authentication authority used to verify user requests.
     */
    private AuthenticationAuthority authAuthority;

    private final Connector connector = new Connector();

    @NotNull
    private final ConferenceRequestHandler conferenceRequestHandler;

    /**
     * Creates new instance of <tt>FocusComponent</tt>.
     */
    public FocusComponent(
            @NotNull XmppComponentConfig config,
            @NotNull ConferenceRequestHandler conferenceRequestHandler)
    {
        super(config.getHostname(), config.getPort(), config.getDomain(), config.getSubdomain(), config.getSecret());

        this.conferenceRequestHandler = conferenceRequestHandler;
    }

    public void setAuthAuthority(AuthenticationAuthority authAuthority)
    {
        this.authAuthority = authAuthority;
    }

    public void loadConfig(ConfigurationService config, String configPropertiesBase)
    {
        super.loadConfig(config, configPropertiesBase);
    }

    public void connect()
    {
        if (!isPingTaskStarted())
        {
            startPingTask();
        }

        connector.connect();
    }

    /**
     * Methods will be invoked by OSGi after {@link #dispose()} is called.
     */
    public void disconnect()
    {
        authAuthority = null;

        connector.disconnect();
    }

    @Override
    public String getDescription()
    {
        return "Manages Jitsi Meet conferences";
    }

    @Override
    public String getName()
    {
        return "Jitsi Meet Focus";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected String[] discoInfoFeatureNamespaces()
    {
        return
            new String[]
                {
                    ConferenceIq.NAMESPACE
                };
    }

    @Override
    protected IQ handleIQGetImpl(IQ iq)
        throws Exception
    {
        try
        {
            org.jivesoftware.smack.packet.IQ smackIq = IQUtils.convert(iq);
            if (smackIq instanceof LoginUrlIq)
            {
                org.jivesoftware.smack.packet.IQ result = handleAuthUrlIq((LoginUrlIq) smackIq);
                return IQUtils.convert(result);
            }
            else
            {
                return super.handleIQGetImpl(iq);
            }
        }
        catch (Exception e)
        {
            logger.error(e, e);
            throw e;
        }
    }

    /**
     * Handles an <tt>org.xmpp.packet.IQ</tt> stanza of type <tt>set</tt> which
     * represents a request.
     *
     * @param iq the <tt>org.xmpp.packet.IQ</tt> stanza of type <tt>set</tt>
     * which represents the request to handle
     * @return an <tt>org.xmpp.packet.IQ</tt> stanza which represents the
     * response to the specified request or <tt>null</tt> to reply with
     * <tt>feature-not-implemented</tt>
     * @throws Exception to reply with <tt>internal-server-error</tt> to the
     * specified request
     */
    @Override
    public IQ handleIQSetImpl(IQ iq)
        throws Exception
    {
        try
        {
            org.jivesoftware.smack.packet.IQ smackIq = IQUtils.convert(iq);

            if (smackIq instanceof ConferenceIq)
            {
                org.jivesoftware.smack.packet.IQ response
                        = conferenceRequestHandler.handleConferenceIq((ConferenceIq) smackIq);

                return IQUtils.convert(response);
            }
            else if (smackIq instanceof LogoutIq)
            {
                logger.info("Logout IQ received: " + iq.toXML());

                if (authAuthority == null)
                {
                    // not-implemented
                    return null;
                }

                org.jivesoftware.smack.packet.IQ smackResult = authAuthority.processLogoutIq((LogoutIq) smackIq);

                return smackResult != null ? IQUtils.convert(smackResult) : null;
            }
            else
            {
                return super.handleIQSetImpl(iq);
            }
        }
        catch (Exception e)
        {
            logger.error(e, e);
            throw e;
        }
    }

    private org.jivesoftware.smack.packet.IQ handleAuthUrlIq(
            LoginUrlIq authUrlIq)
    {
        if (authAuthority == null)
        {
            XMPPError.Builder error = XMPPError.getBuilder(XMPPError.Condition.service_unavailable);
            return org.jivesoftware.smack.packet.IQ.createErrorResponse(authUrlIq, error);
        }

        EntityFullJid peerFullJid = authUrlIq.getFrom().asEntityFullJidIfPossible();
        EntityBareJid roomName = authUrlIq.getRoom();
        if (roomName == null)
        {
            XMPPError.Builder error = XMPPError.getBuilder(XMPPError.Condition.not_acceptable);
            return org.jivesoftware.smack.packet.IQ.createErrorResponse(authUrlIq, error);
        }

        LoginUrlIq result = new LoginUrlIq();
        result.setType(org.jivesoftware.smack.packet.IQ.Type.result);
        result.setStanzaId(authUrlIq.getStanzaId());
        result.setTo(authUrlIq.getFrom());

        boolean popup = authUrlIq.getPopup() != null && authUrlIq.getPopup();

        String machineUID = authUrlIq.getMachineUID();
        if (isBlank(machineUID))
        {
            XMPPError.Builder error
                = XMPPError.from(
                    XMPPError.Condition.bad_request,
                    "missing mandatory attribute 'machineUID'");
            return org.jivesoftware.smack.packet.IQ.createErrorResponse(authUrlIq, error);
        }

        String authUrl = authAuthority.createLoginUrl(machineUID, peerFullJid, roomName, popup);

        result.setUrl(authUrl);

        logger.info("Sending url: " + result.toXML());

        return result;
    }

    /**
     * The code responsible for connecting FocusComponent to the XMPP server.
     */
    private class Connector {
        private ExternalComponentManager componentManager;
        private ScheduledExecutorService executorService;
        private RetryStrategy connectRetry;
        private final Object connectSynRoot = new Object();

        void connect()
        {
            componentManager = new ExternalComponentManager(getHostname(), getPort(), false);
            componentManager.setSecretKey(getSubdomain(), getSecret());
            componentManager.setServerName(getDomain());

            executorService = Executors.newScheduledThreadPool(1);

            init();

            connectRetry = new RetryStrategy(executorService);
            connectRetry.runRetryingTask(new SimpleRetryTask(0, 5000, true, () -> {
                try
                {
                    synchronized (connectSynRoot)
                    {
                        if (componentManager == null)
                        {
                            // Task cancelled ?
                            return false;
                        }

                        componentManager.addComponent(getSubdomain(), FocusComponent.this);

                        return false;
                    }
                }
                catch (ComponentException e)
                {
                    logger.error(e.getMessage() + ", host:" + getHostname() + ", port:" + getPort(), e);
                    return true;
                }
            }));
        }

        void disconnect()
        {
            synchronized (connectSynRoot)
            {
                if (componentManager == null)
                {
                    return;
                }

                if (connectRetry != null)
                {
                    connectRetry.cancel();
                    connectRetry = null;
                }

                if (executorService != null)
                {
                    executorService.shutdown();
                }

                shutdown();
                try
                {
                    componentManager.removeComponent(getSubdomain());
                }
                catch (ComponentException e)
                {
                    logger.error(e, e);
                }

                dispose();

                componentManager = null;
            }
        }
    }
}
