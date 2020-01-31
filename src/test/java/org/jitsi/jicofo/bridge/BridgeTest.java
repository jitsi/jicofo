package org.jitsi.jicofo.bridge;

import org.jitsi.xmpp.extensions.colibri.*;
import org.junit.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;
import org.jxmpp.stringprep.*;

import java.util.*;

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

    private int numberOfConferenceBridges = 4;
    private int numberOfGlobalSenders = 20;
    private int numberOfSpeakers = 2;

    /**
     * 30 kbps for audio, 150 kbps for 180p, 500kbps for 360p and 3200kbps for
     * 720p.
     */
    private int[] bitratesKbps = { 50, 200, 500, 3200 };

    /**
     * Assuming a 100 peeps conference with 20 senders, computes the (max) total
     * bitrate of a bridge that hosts numberOfLocalSenders local senders, numberOfLocalReceivers local receivers and the
     * remaining octo participants.
     *
     * @param numberOfLocalSenders the local senders
     * @param numberOfLocalReceivers the local receivers
     * @return the (max) total bitrate of a bridge that hosts numberOfLocalSenders local senders, numberOfLocalReceivers
     * local receivers and the remaining octo participants
     */
    private int computeMaxUpload(int numberOfLocalSenders, int numberOfLocalReceivers)
    {
        // regardless of the participant distribution, in a 100 people call each
        // sender receivers 19 other senders.
        return (numberOfLocalSenders + numberOfLocalReceivers)
            * (numberOfSpeakers * bitratesKbps[0] + (numberOfGlobalSenders - 2) * bitratesKbps[1] + bitratesKbps[3]);
    }
    private int computeMaxDownload(int numberOfLocalSenders)
    {
        // regardless of the participant distribution, in a 100 people call each
        // sender receivers 19 other senders.
        return numberOfLocalSenders*Arrays.stream(bitratesKbps).sum();
    }

    private int computeMaxOctoSendBitrate(int numberOfLocalSenders)
    {
        // the octo bitrate depends on how many local senders there are.
        return numberOfConferenceBridges * computeMaxDownload(numberOfLocalSenders);
    }

    private int computeMaxOctoReceiveBitrate(int numberOfLocalSenders)
    {
        // the octo bitrate depends on how many local senders there are.
        return computeMaxDownload(numberOfGlobalSenders - numberOfLocalSenders);
    }

    private ColibriStatsExtension createJvbStats(int numberOfLocalSenders, int numberOfLocalReceivers)
    {
        int maxDownload = computeMaxDownload(numberOfLocalSenders)
            , maxUpload = computeMaxUpload(numberOfLocalSenders, numberOfLocalReceivers)
            , maxOctoSendBitrate = computeMaxOctoSendBitrate(numberOfLocalSenders)
            , maxOctoReceiveBitrate = computeMaxOctoReceiveBitrate(numberOfLocalSenders);

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