/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2018 - present 8x8, Inc.
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

import org.jitsi.xmpp.extensions.jitsimeet.*;
import org.jitsi.utils.logging2.*;
import org.jivesoftware.smack.*;

/**
 * Class handles various Jitsi Meet extensions IQs like {@link MuteIq}.
 *
 * @author Pawel Domas
 * @author Boris Grozev
 */
public class IqHandler
{
    /**
     * The logger
     */
    private final static Logger logger = new LoggerImpl(IqHandler.class.getName());

    @NotNull
    private final ConferenceIqHandler conferenceIqHandler;
    private final AuthenticationIqHandler authenticationIqHandler;

    private AbstractXMPPConnection connection;

    /**
     */
    public IqHandler(
            @NotNull ConferenceIqHandler conferenceIqHandler,
            AuthenticationIqHandler authenticationIqHandler)
    {
        this.conferenceIqHandler = conferenceIqHandler;
        this.authenticationIqHandler = authenticationIqHandler;
    }

    /**
     * Initializes this instance and bind packet listeners.
     */
    public void init(AbstractXMPPConnection connection)
    {
        logger.info("Registering IQ handlers with XmppConnection.");
        this.connection = connection;
        connection.registerIQRequestHandler(conferenceIqHandler);
        if (authenticationIqHandler != null)
        {
            connection.registerIQRequestHandler(authenticationIqHandler.getLoginUrlIqHandler());
            connection.registerIQRequestHandler(authenticationIqHandler.getLogoutIqHandler());
        }
    }

    public void shutdown()
    {
        if (connection != null && authenticationIqHandler != null)
        {
            connection.unregisterIQRequestHandler(authenticationIqHandler.getLoginUrlIqHandler());
            connection.unregisterIQRequestHandler(authenticationIqHandler.getLogoutIqHandler());
        }
    }

}
