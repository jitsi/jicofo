package org.jitsi.impl.protocol.xmpp;

import net.java.sip.communicator.util.*;
import org.jivesoftware.smack.*;
import org.jivesoftware.smack.debugger.*;

import java.io.*;
import java.util.*;

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
    private static final WeakHashMap<XMPPConnection, PacketDebugger> debuggerMap
        = new WeakHashMap<>();

    /**
     * The logger used by this class.
     */
    private final static Logger logger
        = Logger.getLogger(PacketDebugger.class);

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

    static private boolean packetLoggingEnabled = false;

    static public boolean isPacketLoggingEnabled()
    {
        return packetLoggingEnabled;
    }

    static public void setPacketLoggingEnabled(boolean enabled)
    {
        packetLoggingEnabled = enabled;
    }

    /**
     * Total XMPP packets  received.
     */
    private volatile long totalPacketsRecv = 0;

    /**
     * Total XMPP packets sent.
     */
    private volatile long totalPacketsSent = 0;

    /**
     * Creates new {@link PacketDebugger}
     * {@inheritDoc}
     */
    public PacketDebugger(XMPPConnection connection, Writer writer, Reader reader)
    {
        super(connection, writer, reader);

        debuggerMap.put(connection, this);
    }

    /**
     * @return total XMPP packets received for the lifetime of the tracked
     * {@link XMPPConnection} instance.
     */
    public long getTotalPacketsRecv()
    {
        return totalPacketsRecv;
    }

    /**
     * @return total XMPP packets sent for the lifetime of the tracked
     * {@link XMPPConnection} instance.
     */
    public long getTotalPacketsSent()
    {
        return totalPacketsSent;
    }

    // It's fine to do non-atomic as it's only 1 thread doing write operation
    /**
     * {@inheritDoc}
     */
    @SuppressWarnings("NonAtomicOperationOnVolatileField")
    @Override
    protected void log(String logMessage)
    {
        if (logMessage.contains("SENT"))
        {
            totalPacketsSent += 1;
        }
        else if (logMessage.contains("RECV"))
        {
            totalPacketsRecv += 1;
        }

        if (packetLoggingEnabled)
        {
            logger.info(logMessage);
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
