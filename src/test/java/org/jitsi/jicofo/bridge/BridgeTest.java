package org.jitsi.jicofo.bridge;

import org.jitsi.xmpp.extensions.colibri.*;
import org.junit.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;
import org.jxmpp.stringprep.*;

import static org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.*;

public class BridgeTest
{

    @Test
    public void getStress()
        throws
        XmppStringprepException
    {
        // Everything should work regardless of the type of jid.
        Jid jvb1Jid = JidCreate.from("jvb.example.com");
        Bridge bridge = new Bridge(null, jvb1Jid, null);

        // In the most balanced scenario, in a 100 people call over 4 bridges,
        // each bridge handles 25 participants. Note that we can't know in
        // advance whether a participant will be sendrecv or a recvonly.

        bridge.setStats(createJvbStats(0 /* numOf local senders */, 0 /* numOf local receivers */));
        Assert.assertEquals(bridge.getStress(), 0.14, .05);

        bridge.setStats(createJvbStats(5 /* numOf local senders */, 0 /* numOf local receivers */));
        Assert.assertEquals(bridge.getStress(), 0.34, .05);

        bridge.setStats(createJvbStats(5 /* numOf local senders */, 5 /* numOf local receivers */));
        Assert.assertEquals(bridge.getStress(), 0.4, .05);

        bridge.setStats(createJvbStats(10 /* numOf local senders */, 5 /* numOf local receivers */));
        Assert.assertEquals(bridge.getStress(), 0.6, .05);

        bridge.setStats(createJvbStats(10 /* numOf local senders */, 10 /* numOf local receivers */));
        Assert.assertEquals(bridge.getStress(), 0.66, .05);

        bridge.setStats(createJvbStats(15 /* numOf local senders */, 10 /* numOf local receivers */));
        Assert.assertEquals(bridge.getStress(), 0.86, .05);

        bridge.setStats(createJvbStats(20 /* numOf local senders */, 5 /* numOf local receivers */));
        Assert.assertEquals(bridge.getStress(), 1.0, .05);

        bridge.setStats(createJvbStats(5 /* numOf local senders */, 20 /* numOf local receivers */));
        Assert.assertEquals(bridge.getStress(), 0.58, .05);
    }

    private ColibriStatsExtension createJvbStats(int numberOfLocalSenders, int numberOfLocalReceivers)
    {
        MaxBitrateCalculator maxBitrateCalculator = new MaxBitrateCalculator(
            4 /* numberOfConferenceBridges */,
            20 /* numberOfGlobalSenders */,
            2 /* numberOfSpeakers */
        );

        int maxDownload = maxBitrateCalculator.computeMaxDownload(numberOfLocalSenders)
            , maxUpload = maxBitrateCalculator.computeMaxUpload(numberOfLocalSenders, numberOfLocalReceivers)
            , maxOctoSendBitrate = maxBitrateCalculator.computeMaxOctoSendBitrate(numberOfLocalSenders)
            , maxOctoReceiveBitrate = maxBitrateCalculator.computeMaxOctoReceiveBitrate(numberOfLocalSenders);

        ColibriStatsExtension statsExtension = new ColibriStatsExtension();

        statsExtension.addStat(
            new ColibriStatsExtension.Stat(
                BITRATE_DOWNLOAD, maxDownload));
        statsExtension.addStat(
            new ColibriStatsExtension.Stat(
                BITRATE_UPLOAD, maxUpload));
        statsExtension.addStat(
            new ColibriStatsExtension.Stat(
                OCTO_RECEIVE_BITRATE, maxOctoReceiveBitrate));
        statsExtension.addStat(
            new ColibriStatsExtension.Stat(
                OCTO_SEND_BITRATE, maxOctoSendBitrate));

        return statsExtension;
    }
}