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
package mock.media;

import org.jitsi.impl.neomedia.*;
import org.jitsi.impl.neomedia.rtp.*;
import org.jitsi.impl.neomedia.transform.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.service.neomedia.device.*;
import org.jitsi.service.neomedia.format.*;
import org.jitsi.service.neomedia.rtp.*;
import org.jitsi.service.neomedia.stats.*;

import java.net.*;
import java.util.*;

/**
 *
 * @author Pawel Domas
 * @author Lyubomir Marinov
 */
public class MockMediaStream
    extends AbstractMediaStream
{
    private MockMediaStreamStats streamStats = new MockMediaStreamStats();

    @Override
    public void addDynamicRTPPayloadType(byte rtpPayloadType,
                                         MediaFormat format)
    {
    }

    @Override
    public void addDynamicRTPPayloadTypeOverride(byte originalPt,
                                                 byte overloadPt)
    {
    }

    @Override
    public void addRTPExtension(byte extensionID, RTPExtension rtpExtension)
    {
    }

    @Override
    public void clearRTPExtensions()
    {
    }

    @Override
    public void clearDynamicRTPPayloadTypes()
    {
    }

    @Override
    public void close()
    {
    }

    @Override
    public RetransmissionRequester getRetransmissionRequester()
    {
        return null;
    }

    @Override
    public Map<Byte, RTPExtension> getActiveRTPExtensions()
    {
        return null;
    }

    @Override
    public MediaDevice getDevice()
    {
        return null;
    }

    @Override
    public MediaDirection getDirection()
    {
        return null;
    }

    @Override
    public Map<Byte, MediaFormat> getDynamicRTPPayloadTypes()
    {
        return new HashMap<>();
    }

    @Override
    public MediaFormat getFormat()
    {
        return null;
    }

    @Override
    public long getLocalSourceID()
    {
        return 0;
    }

    @Override
    public MediaStreamStats2 getMediaStreamStats()
    {
        return streamStats;
    }

    @Override
    public InetSocketAddress getRemoteControlAddress()
    {
        return null;
    }

    @Override
    public InetSocketAddress getRemoteDataAddress()
    {
        return null;
    }

    @Override
    public long getRemoteSourceID()
    {
        return 0;
    }

    @Override
    public List<Long> getRemoteSourceIDs()
    {
        return null;
    }

    @Override
    public SrtpControl getSrtpControl()
    {
        return null;
    }

    @Override
    public StreamRTPManager getStreamRTPManager()
    {
        return null;
    }

    @Override
    public MediaStreamTarget getTarget()
    {
        return null;
    }

    @Override
    public StreamConnector.Protocol getTransportProtocol()
    {
        return null;
    }

    @Override
    public void injectPacket(RawPacket pkt, boolean data, TransformEngine after)
    {
    }

    @Override
    public boolean isKeyFrame(RawPacket pkt)
    {
        return false;
    }

    @Override
    public boolean isKeyFrame(byte[] buf, int off, int len)
    {
        // TODO move to AbstractMediaStream.
        return false;
    }

    @Override
    public boolean isMute()
    {
        return false;
    }

    @Override
    public boolean isStarted()
    {
        return false;
    }

    @Override
    public void removeReceiveStreamForSsrc(long ssrc)
    {
    }

    @Override
    public void setConnector(StreamConnector connector)
    {
    }

    @Override
    public void setDevice(MediaDevice device)
    {
    }

    @Override
    public void setDirection(MediaDirection direction)
    {
    }

    @Override
    public void setExternalTransformer(TransformEngine transformEngine)
    {
    }

    @Override
    public void setFormat(MediaFormat format)
    {
    }

    @Override
    public void setMute(boolean mute)
    {
    }

    @Override
    public void setRTPTranslator(RTPTranslator rtpTranslator)
    {
    }

    @Override
    public void setSSRCFactory(SSRCFactory ssrcFactory)
    {
    }

    @Override
    public void setTarget(MediaStreamTarget target)
    {
    }

    @Override
    public void start()
    {
    }

    @Override
    public void stop()
    {
    }
}
