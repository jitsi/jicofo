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
import net.java.sip.communicator.impl.protocol.jabber.extensions.health.*;
import net.java.sip.communicator.util.*;

import net.java.sip.communicator.util.Logger;
import org.jitsi.impl.neomedia.rtp.*;
import org.jitsi.util.*;
import org.jitsi.videobridge.*;

import org.jivesoftware.smack.*;
import org.jivesoftware.smack.filter.*;
import org.jivesoftware.smack.packet.*;

import org.osgi.framework.*;

import java.util.*;

/**
 *
 * @author Pawel Domas
 */
public class MockVideobridge
    implements PacketFilter, PacketListener, BundleActivator
{
    /**
     * The logger
     */
    private static final Logger logger
        = Logger.getLogger(MockVideobridge.class);

    private final MockXmppConnection connection;

    private final String bridgeJid;

    private Videobridge bridge;

    private XMPPError.Condition error;

    private boolean returnServerError = false;

    private VideobridgeBundleActivator jvbActivator;

    public MockVideobridge(MockXmppConnection connection,
                           String bridgeJid)
    {
        this.connection = connection;
        this.bridgeJid = bridgeJid;
    }

    public void start(BundleContext bc)
        throws Exception
    {
        this.jvbActivator = new VideobridgeBundleActivator();

        jvbActivator.start(bc);

        bridge = ServiceUtils.getService(bc, Videobridge.class);

        connection.addPacketHandler(this, this);
    }

    @Override
    public void stop(BundleContext bundleContext)
        throws Exception
    {
        connection.removePacketHandler(this);

        jvbActivator.stop(bundleContext);
    }

    @Override
    public boolean accept(Packet packet)
    {
        return bridgeJid.equals(packet.getTo());
    }

    public void processPacket(Packet p)
    {
        if (p instanceof ColibriConferenceIQ
                || p instanceof HealthCheckIQ)
        {
            logger.debug("JVB rcv: " + p.toXML());

            IQ response;
            if (error == null)
            {
                try
                {
                    response = processImpl((IQ) p);
                }
                catch (Exception e)
                {
                    response = null;
                    logger.error("JVB internal error!", e);
                }
            }
            else
            {
                response = IQ.createErrorResponse((IQ) p, new XMPPError(error));
            }

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
                logger.warn("The bridge sent no response to " + p.toXML());
            }
        }
        else if (p != null)
        {
            logger.error(bridgeJid + " has discarded " + p.toXML());
        }
    }

    /**
     *
     * @param p <tt>ColibriConferenceIQ</tt> or <tt>HealthCheckIQ</tt> assumed
     * @return
     * @throws Exception
     */
    private IQ processImpl(IQ p)
        throws Exception
    {
        if (isReturnServerError())
        {
            return
                IQ.createErrorResponse(
                    p,
                    new XMPPError(
                        XMPPError.Condition.interna_server_error));
        }
        else if (p instanceof ColibriConferenceIQ)
        {
            return
                bridge.handleColibriConferenceIQ(
                        (ColibriConferenceIQ) p,
                        Videobridge.OPTION_ALLOW_ANY_FOCUS);
        }
        else
        {
            return bridge.handleHealthCheckIQ((HealthCheckIQ) p);
        }
    }

    public List<RTPEncodingDesc> getSimulcastLayers(
            String confId, String channelId)
    {
        Conference conference = bridge.getConference(confId, null);
        Content videoContent = conference.getOrCreateContent("video");
        VideoChannel videoChannel
            = (VideoChannel) videoContent.getChannel(channelId);

        MediaStreamTrackDesc[] tracks = videoChannel
            .getStream()
            .getMediaStreamTrackReceiver()
            .getMediaStreamTracks();

        if (ArrayUtils.isNullOrEmpty(tracks))
            return new ArrayList<>();

        RTPEncodingDesc[] layers = tracks[0].getRTPEncodings();
        if (ArrayUtils.isNullOrEmpty(layers))
            return new ArrayList<>();

        return Arrays.asList(layers);
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

    public int getConferenceCount()
    {
        return bridge.getConferenceCount();
    }

    public void setError(XMPPError.Condition error)
    {
        this.error = error;
    }

    public XMPPError.Condition getError()
    {
        return error;
    }

    public boolean isReturnServerError()
    {
        return returnServerError;
    }

    public void setReturnServerError(boolean returnServerError)
    {
        this.returnServerError = returnServerError;
    }
}
