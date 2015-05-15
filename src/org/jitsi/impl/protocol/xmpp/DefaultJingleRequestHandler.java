/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
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
    public void onTransportInfo(JingleSession jingleSession,
                                List<ContentPacketExtension> contents)
    {
        logger.warn("Ignored Jingle 'transport-info'");
    }
}
