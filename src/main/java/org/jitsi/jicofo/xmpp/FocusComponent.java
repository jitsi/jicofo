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
import org.jitsi.service.configuration.*;
import org.jitsi.utils.logging.*;
import org.jitsi.xmpp.component.*;
import org.jitsi.xmpp.util.*;

import org.jivesoftware.smack.packet.*;

import org.jivesoftware.whack.ExternalComponentManager;
import org.xmpp.component.ComponentException;
import org.xmpp.packet.IQ;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

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

    private final Connector connector = new Connector();

    @NotNull
    private final ConferenceRequestHandler conferenceRequestHandler;

    @Nullable
    private final AuthenticationIqHandler authenticationIqHandler;
    /**
     * Creates new instance of <tt>FocusComponent</tt>.
     */
    public FocusComponent(
            @NotNull XmppComponentConfig config,
            @NotNull ConferenceRequestHandler conferenceRequestHandler,
            @Nullable AuthenticationIqHandler authenticationIqHandler)
    {
        super(config.getHostname(), config.getPort(), config.getDomain(), config.getSubdomain(), config.getSecret());

        this.conferenceRequestHandler = conferenceRequestHandler;
        this.authenticationIqHandler = authenticationIqHandler;
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
                LoginUrlIq loginUrlIq = (LoginUrlIq) smackIq;
                org.jivesoftware.smack.packet.IQ response;
                if (authenticationIqHandler == null)
                {
                    XMPPError.Builder error = XMPPError.getBuilder(XMPPError.Condition.service_unavailable);
                    response = org.jivesoftware.smack.packet.IQ.createErrorResponse(loginUrlIq, error);
                }
                else
                {
                    response = authenticationIqHandler.handleLoginUrlIq(loginUrlIq);
                }

                return IQUtils.convert(response);
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

                if (authenticationIqHandler == null)
                {
                    // not-implemented
                    return null;
                }

                return IQUtils.convert(authenticationIqHandler.handleLogoutUrlIq((LogoutIq) smackIq));
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
