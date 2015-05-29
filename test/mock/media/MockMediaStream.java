package mock.media;

import org.jitsi.impl.neomedia.*;
import org.jitsi.impl.neomedia.transform.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.service.neomedia.device.*;
import org.jitsi.service.neomedia.format.*;

import java.net.*;
import java.util.*;

/**
 *
 */
public class MockMediaStream
    extends AbstractMediaStream
{
    @Override
    public void start()
    {

    }

    @Override
    public void stop()
    {

    }

    @Override
    public void setExternalTransformer(TransformEngine transformEngine)
    {

    }

    @Override
    public void injectPacket(RawPacket rawPacket, boolean b, boolean b1)
    {

    }

    @Override
    public void close()
    {

    }

    @Override
    public void setFormat(MediaFormat format)
    {

    }

    @Override
    public MediaFormat getFormat()
    {
        return null;
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
    public MediaDevice getDevice()
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
    public long getLocalSourceID()
    {
        return 0;
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
    public StreamConnector.Protocol getTransportProtocol()
    {
        return null;
    }

    @Override
    public void setTarget(MediaStreamTarget target)
    {

    }

    @Override
    public MediaStreamTarget getTarget()
    {
        return null;
    }

    @Override
    public void addDynamicRTPPayloadType(byte rtpPayloadType,
                                         MediaFormat format)
    {

    }

    @Override
    public void clearDynamicRTPPayloadTypes()
    {

    }

    @Override
    public Map<Byte, MediaFormat> getDynamicRTPPayloadTypes()
    {
        return null;
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
    public Map<Byte, RTPExtension> getActiveRTPExtensions()
    {
        return null;
    }

    @Override
    public void setDirection(MediaDirection direction)
    {

    }

    @Override
    public MediaDirection getDirection()
    {
        return null;
    }

    @Override
    public boolean isStarted()
    {
        return false;
    }

    @Override
    public void setMute(boolean mute)
    {

    }

    @Override
    public boolean isMute()
    {
        return false;
    }

    @Override
    public SrtpControl getSrtpControl()
    {
        return null;
    }

    @Override
    public RTPTranslator getRTPTranslator()
    {
        return null;
    }

    @Override
    public void setRTPTranslator(RTPTranslator rtpTranslator)
    {

    }

    @Override
    public MediaStreamStats getMediaStreamStats()
    {
        return null;
    }

    @Override
    public void removeReceiveStreamForSsrc(long ssrc)
    {

    }

    @Override
    public void setSSRCFactory(SSRCFactory ssrcFactory)
    {

    }
}
