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
package org.jitsi.impl.protocol.xmpp;

import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import net.java.sip.communicator.util.*;
import org.jitsi.protocol.xmpp.*;

import java.util.*;

/**
 * Stub for {@link JingleRequestHandler}, so that we don't have to implement all
 * methods and waste lines of code.
 *
 * @author Pawel Domas
 */
public class DefaultJingleRequestHandler
    implements JingleRequestHandler
{
    /**
     * The logger used by this instance.
     */
    private final static Logger logger
        = Logger.getLogger(DefaultJingleRequestHandler.class);

    @Override
    public void onAddSource(JingleSession jingleSession,
                            List<ContentPacketExtension> contents)
    {
        logger.warn("Ignored Jingle 'source-add'");
    }

    @Override
    public void onRemoveSource(JingleSession jingleSession,
                               List<ContentPacketExtension> contents)
    {
        logger.warn("Ignored Jingle 'source-remove'");
    }

    @Override
    public void onSessionAccept(JingleSession jingleSession,
                                List<ContentPacketExtension> answer)
    {
        logger.warn("Ignored Jingle 'session-accept'");
    }

    @Override
    public void onTransportAccept(JingleSession jingleSession,
                                  List<ContentPacketExtension> contents)
    {
        logger.warn("Ignored Jingle 'transport-accept'");
    }

    @Override
    public void onTransportInfo(JingleSession jingleSession,
                                List<ContentPacketExtension> contents)
    {
        logger.warn("Ignored Jingle 'transport-info'");
    }

    @Override
    public void onTransportReject(JingleSession jingleSession,
                                  JingleIQ      rejectIQ)
    {
        logger.warn("Ignored Jingle 'transport-reject'");
    }
}
