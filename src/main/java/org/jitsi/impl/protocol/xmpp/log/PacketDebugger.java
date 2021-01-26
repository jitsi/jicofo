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

import java.io.*;
import java.lang.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/**
 * Implements {@link SmackDebugger} in order to get info about XMPP traffic.
 */
public class PacketDebugger
    extends AbstractDebugger
{
    /**
     * Weak mapping between {@link XMPPConnection} and {@link PacketDebugger}
     * for convenient access(XMPP connection doesn't have a getter for
     * the debugger field).
     */
    private static final WeakHashMap<XMPPConnection, PacketDebugger> debuggerMap = new WeakHashMap<>();

    /**
     * The logger used by this class.
     */
    private final static Logger logger = new LoggerImpl(PacketDebugger.class.getName());

    /**
     * Finds {@link PacketDebugger} for given connection.
     * @param connection - the connection for which {@link PacketDebugger} will
     * be retrieved.
     * @return debugger instance for given connection.
     */
    static public PacketDebugger forConnection(XMPPConnection connection)
    {
        return debuggerMap.get(connection);
    }

    /**
     * Total XMPP packets  received.
     */
    private AtomicLong totalPacketsRecv = new AtomicLong();

    /**
     * Total XMPP packets sent.
     */
    private AtomicLong totalPacketsSent = new AtomicLong();

    /**
     * Creates new {@link PacketDebugger}
     * {@inheritDoc}
     */
    @SuppressFBWarnings("ST_WRITE_TO_STATIC_FROM_INSTANCE_METHOD")
    public PacketDebugger(XMPPConnection connection, Writer writer, Reader reader)
    {
        super(connection, writer, reader);

        // Change the static value only if an instance is created.
        AbstractDebugger.printInterpreted = true;

        debuggerMap.put(connection, this);
    }

    /**
     * @return total XMPP packets received for the lifetime of the tracked
     * {@link XMPPConnection} instance.
     */
    public long getTotalPacketsRecv()
    {
        return totalPacketsRecv.get();
    }

    /**
     * @return total XMPP packets sent for the lifetime of the tracked
     * {@link XMPPConnection} instance.
     */
    public long getTotalPacketsSent()
    {
        return totalPacketsSent.get();
    }

    // It's fine to do non-atomic as it's only 1 thread doing write operation
    /**
     * {@inheritDoc}
     */
    @Override
    protected void log(String logMessage)
    {
        if (logMessage.startsWith("SENT"))
        {
            totalPacketsSent.incrementAndGet();
        }
        else if (logMessage.startsWith("RCV PKT ("))
        {
            totalPacketsRecv.incrementAndGet();
        }

        if (logger.isDebugEnabled() && !logMessage.startsWith("RECV ("))
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
