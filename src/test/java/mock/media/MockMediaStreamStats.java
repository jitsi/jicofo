package mock.media;

import org.jitsi.service.neomedia.rtp.*;
import org.jitsi.service.neomedia.stats.*;

import java.awt.*;
import java.util.*;

import static org.mockito.Mockito.*;

/**
 *
 */
public class MockMediaStreamStats
    implements MediaStreamStats2
{
    @Override
    public double getDownloadJitterMs()
    {
        return 0;
    }

    @Override
    public double getDownloadPercentLoss()
    {
        return 0;
    }

    @Override
    public double getDownloadRateKiloBitPerSec()
    {
        return 0;
    }

    @Override
    public Dimension getDownloadVideoSize()
    {
        return null;
    }

    @Override
    public String getEncoding()
    {
        return null;
    }

    @Override
    public String getEncodingClockRate()
    {
        return null;
    }

    @Override
    public int getJitterBufferDelayMs()
    {
        return 0;
    }

    @Override
    public int getJitterBufferDelayPackets()
    {
        return 0;
    }

    @Override
    public String getLocalIPAddress()
    {
        return null;
    }

    @Override
    public int getLocalPort()
    {
        return 0;
    }

    @Override
    public long getNbReceivedBytes()
    {
        return 0;
    }

    @Override
    public long getNbSentBytes()
    {
        return 0;
    }

    @Override
    public long getNbDiscarded()
    {
        return 0;
    }

    @Override
    public int getNbDiscardedFull()
    {
        return 0;
    }

    @Override
    public int getNbDiscardedLate()
    {
        return 0;
    }

    @Override
    public int getNbDiscardedReset()
    {
        return 0;
    }

    @Override
    public int getNbDiscardedShrink()
    {
        return 0;
    }

    @Override
    public long getNbFec()
    {
        return 0;
    }

    @Override
    public long getNbPackets()
    {
        return 0;
    }

    @Override
    public long getNbPacketsLost()
    {
        return 0;
    }

    @Override
    public int getPacketQueueCountPackets()
    {
        return 0;
    }

    @Override
    public int getPacketQueueSize()
    {
        return 0;
    }

    @Override
    public double getPercentDiscarded()
    {
        return 0;
    }

    @Override
    public String getRemoteIPAddress()
    {
        return null;
    }

    @Override
    public int getRemotePort()
    {
        return 0;
    }

    @Override
    public RTCPReports getRTCPReports()
    {
        return null;
    }

    @Override
    public long getRttMs()
    {
        return 0;
    }

    @Override
    public double getUploadJitterMs()
    {
        return 0;
    }

    @Override
    public double getUploadPercentLoss()
    {
        return 0;
    }

    @Override
    public double getUploadRateKiloBitPerSec()
    {
        return 0;
    }

    @Override
    public Dimension getUploadVideoSize()
    {
        return null;
    }

    @Override
    public boolean isAdaptiveBufferEnabled()
    {
        return false;
    }

    @Override
    public void updateStats()
    {

    }

    @Override
    public double getMinDownloadJitterMs()
    {
        return 0;
    }

    @Override
    public double getMaxDownloadJitterMs()
    {
        return 0;
    }

    @Override
    public double getAvgDownloadJitterMs()
    {
        return 0;
    }

    @Override
    public double getMinUploadJitterMs()
    {
        return 0;
    }

    @Override
    public double getMaxUploadJitterMs()
    {
        return 0;
    }

    @Override
    public double getAvgUploadJitterMs()
    {
        return 0;
    }

    @Override
    public long getNbPacketsSent()
    {
        return 0;
    }

    @Override
    public long getNbPacketsReceived()
    {
        return 0;
    }

    @Override
    public long getDownloadNbPacketLost()
    {
        return 0;
    }

    @Override
    public long getUploadNbPacketLost()
    {
        return 0;
    }

    @Override
    public void addRTCPPacketListener(RTCPPacketListener listener)
    {

    }

    @Override
    public void removeRTCPPacketListener(RTCPPacketListener listener)
    {

    }

    @Override
    public long getSendingBitrate()
    {
        return 0;
    }

    @Override
    public ReceiveTrackStats getReceiveStats()
    {
        return mock(ReceiveTrackStats.class);
    }

    @Override
    public SendTrackStats getSendStats()
    {
        return mock(SendTrackStats.class);
    }

    @Override
    public ReceiveTrackStats getReceiveStats(long ssrc)
    {
        return null;
    }

    @Override
    public SendTrackStats getSendStats(long ssrc)
    {
        return null;
    }

    @Override
    public Collection<? extends SendTrackStats> getAllSendStats()
    {
        return null;
    }

    @Override
    public Collection<? extends ReceiveTrackStats> getAllReceiveStats()
    {
        return null;
    }
}
