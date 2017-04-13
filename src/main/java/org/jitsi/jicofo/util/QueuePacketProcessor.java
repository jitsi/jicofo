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

import net.java.sip.communicator.util.*;
import org.jivesoftware.smack.*;
import org.jivesoftware.smack.packet.*;

import java.util.concurrent.*;

/**
 * Process incoming xmpp packets, queues them and process them by passing them
 * to the specified packed listener in a single thread.
 *
 * @author Damian Minkov
 */
public class QueuePacketProcessor
    implements PacketListener
{
    /**
     * The packet listener to the real processing.
     */
    private PacketListener packetListener;

    /**
     * The single thread executor that will process packets.
     */
    private ExecutorService executor;

    /**
     * Constructs QueuePacketProcessor, taking the PacketListener that will
     * be used to process.
     * @param packetListener
     */
    public QueuePacketProcessor(
        PacketListener packetListener)
    {
        this.packetListener = packetListener;

        this.executor = Executors.newSingleThreadExecutor();
    }

    @Override
    public void processPacket(final Packet packet)
    {
        // add the packet to the queue of tasks to process
        executor.submit(new Runnable()
        {
            @Override
            public void run()
            {
                packetListener.processPacket(packet);
            }
        });
    }

    /**
     * Stops processing.
     */
    public void stop()
    {
        this.executor.shutdown();
        this.executor = null;
    }
}
