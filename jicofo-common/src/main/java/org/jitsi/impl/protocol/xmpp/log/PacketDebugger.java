/*
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
package org.jitsi.impl.protocol.xmpp.log;

import edu.umd.cs.findbugs.annotations.*;
import org.jitsi.utils.logging2.*;
import org.jivesoftware.smack.*;
import org.jivesoftware.smack.debugger.*;
import org.jivesoftware.smack.packet.*;

import java.lang.*;

/**
 * Implements {@link SmackDebugger} in order to get info about XMPP traffic.
 */
public class PacketDebugger
    extends AbstractDebugger
{
    /**
     * The logger used by this class.
     */
    private final static Logger logger = new LoggerImpl(PacketDebugger.class.getName());

    /**
     * Whether XMPP logging is enabled. We don't want to insert a debugger into Smack when it's not going to actually
     * log anything.
     */
    public static boolean isEnabled()
    {
        return logger.isDebugEnabled();
    }

    /**
     * An ID to log to identify the connection.
     */
    @NonNull
    private final String id;

    /**
     * Creates new {@link PacketDebugger}
     * {@inheritDoc}
     */
    @SuppressFBWarnings("ST_WRITE_TO_STATIC_FROM_INSTANCE_METHOD")
    public PacketDebugger(XMPPConnection connection, @NonNull String id)
    {
        super(connection);
        this.id = id;

        // Change the static value only if an instance is created.
        AbstractDebugger.printInterpreted = true;
    }

    @Override
    public void onIncomingStreamElement(TopLevelStreamElement streamElement)
    {
        logger.debug(() -> "RCV PKT (" + id + "): " + streamElement.toXML());
    }

    @Override
    public void onOutgoingStreamElement(TopLevelStreamElement streamElement)
    {
        logger.debug(() -> "SENT PKT (" + id + "): " + streamElement.toXML());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void log(String logMessage)
    {
        if (logger.isDebugEnabled() && !logMessage.startsWith("RECV (") && !logMessage.startsWith("SENT ("))
        {
            logger.debug(logMessage);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void log(String logMessage, Throwable throwable)
    {
        logger.warn("Smack: " + logMessage, throwable);
    }
}
