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
package org.jitsi.jicofo.util;

import org.jitsi.protocol.xmpp.*;

import org.jivesoftware.smack.*;
import org.jivesoftware.smack.filter.*;
import org.jivesoftware.smack.packet.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * Process incoming xmpp packets, queues them and process them by passing them
 * to the specified packed listener in a single thread.
 *
 * @author Damian Minkov
 * @author Pawel Domas
 */
public class QueuePacketProcessor
{
    /**
     * The single thread executor that will process packets.
     */
    private ExecutorService executor;

    /**
     * The packet filter which will be used to filter packets to be processed by
     * {@link #packetListener}.
     */
    private final PacketFilter packetFilter;

    /**
     * The packet listener to the real processing.
     */
    private PacketListener packetListener;

    /**
     * The XMPP connection to which {@link #packetListener} will be registered.
     */
    private final XmppConnection connection;

    /**
     * Stores instance of {@link #packetListener} wrapper to be able
     * to unregister it correctly.
     */
    private PacketListener _packetListenerWrap;

    /**
     * Constructs QueuePacketProcessor, taking the PacketListener that will
     * be used to process.
     * @param connection the connection instance to which packet listener will
     * be registered.
     * @param packetListener target packet processor.
     * @param packetFilter packet filter used to limit packets acceptable by
     * the packet listener.
     */
    public QueuePacketProcessor(
            XmppConnection    connection,
            PacketListener    packetListener,
            PacketFilter      packetFilter)
    {
        this.packetListener
            = Objects.requireNonNull(packetListener, "packetListener");
        this.packetFilter
            = Objects.requireNonNull(packetFilter, "packetFilter");
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    /**
     * Binds the underling packet listener to the XMPP connection and starts
     * the executor on which packets will be processed.
     */
    public void start()
    {
        if (executor != null)
        {
            throw new IllegalStateException("already started");
        }

        final ExecutorService theExecutorService
            = this.executor = Executors.newSingleThreadExecutor();

        this._packetListenerWrap = new PacketListener()
        {
            @Override
            public void processPacket(final Packet packet)
            {
                // add the packet to the queue of tasks to process
                theExecutorService.submit(new Runnable()
                {
                    @Override
                    public void run()
                    {
                        // If this instance was stopped executor will change
                        if (QueuePacketProcessor.this.executor
                                == theExecutorService)
                        {
                            packetListener.processPacket(packet);
                        }
                    }
                });
            }
        };

        this.connection.addPacketHandler(_packetListenerWrap, packetFilter);
    }

    /**
     * Stops processing.
     */
    public void stop()
    {
        if (executor == null)
        {
            throw new IllegalStateException("already stopped");
        }
        this.connection.removePacketHandler(_packetListenerWrap);
        this.executor.shutdown();
        this.executor = null;
    }
}
