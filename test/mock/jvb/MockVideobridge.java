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
package mock.jvb;

import mock.xmpp.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.util.*;
import org.jitsi.videobridge.*;
import org.jitsi.videobridge.simulcast.*;
import org.jivesoftware.smack.packet.*;
import org.osgi.framework.*;

import java.util.*;

/**
 *
 * @author Pawel Domas
 */
public class MockVideobridge
{
    /**
     * The logger
     */
    private final static Logger logger
        = Logger.getLogger(MockVideobridge.class);

    private final MockXmppConnection connection;

    private final String bridgeJid;

    private Thread thread;

    private boolean run = true;

    private Videobridge bridge;

    public MockVideobridge(BundleContext bc,
                           MockXmppConnection connection,
                           String bridgeJid)
    {
        this.connection = connection;

        VideobridgeBundleActivator activator
            = new VideobridgeBundleActivator();
        try
        {
            activator.start(bc);
        }
        catch (Exception e)
        {
            logger.error(e, e);
        }

        bridge = ServiceUtils.getService(bc, Videobridge.class);

        this.bridgeJid = bridgeJid;
    }

    public void start()
    {
        this.thread = new Thread(new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    jvbLoop();
                }
                catch (Exception e)
                {
                    logger.error(e, e);
                }
            }
        });

        thread.start();
    }

    private void jvbLoop()
        throws Exception
    {
        while (run)
        {
            Packet p = connection.readNextPacket(bridgeJid, 500);
            if (p instanceof ColibriConferenceIQ)
            {
                logger.debug("JVB rcv: " + p.toXML());

                IQ response
                    = bridge.handleColibriConferenceIQ(
                            (ColibriConferenceIQ) p,
                            Videobridge.OPTION_ALLOW_ANY_FOCUS);

                if (response != null)
                {
                    response.setTo(p.getFrom());
                    response.setFrom(bridgeJid);
                    if (IQ.Type.RESULT.equals(response.getType()))
                    {
                        response.setPacketID(p.getPacketID());
                    }
                    connection.sendPacket(response);

                    logger.debug("JVB sent: " + response.toXML());
                }
                else
                {
                    logger.warn("The bridge sent no response for "
                                    + p.toString());
                }
            }
            else if (p != null)
            {
                logger.error("Discarded " + p.toXML());
            }
        }
    }

    public SortedSet<SimulcastLayer> getSimulcastLayers(
        String confId, String channelId)
    {
        Conference conference = bridge.getConference(confId, null);
        Content videoContent = conference.getOrCreateContent("video");
        VideoChannel videoChannel
            = (VideoChannel) videoContent.getChannel(channelId);
        return videoChannel.getSimulcastManager().getSimulcastLayers();
    }

    public int getChannelsCount()
    {
        int count = 0;
        for (Conference conference : bridge.getConferences())
        {
            for (Content content: conference.getContents())
            {
                count += content.getChannelCount();
            }
        }
        return count;
    }

    public int getChannelCountByContent(String contentName)
    {
        int count = 0;
        boolean any = false;

        for (Conference conference : bridge.getConferences())
        {
            for (Content content: conference.getContents())
            {
                if (contentName.equals(content.getName()))
                {
                    any = true;
                    count += content.getChannelCount();
                }
            }
        }
        return any ? count : -1;
    }

    public String getBridgeJid()
    {
        return bridgeJid;
    }
}
