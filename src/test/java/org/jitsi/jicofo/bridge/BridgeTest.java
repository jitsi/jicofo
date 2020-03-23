package org.jitsi.jicofo.bridge;

import org.jitsi.jicofo.util.*;
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
        Assert.assertEquals(0.0, bridge.getStress(), .05);

        bridge.setStats(createJvbStats(5 /* numOf local senders */, 0 /* numOf local receivers */));
        Assert.assertEquals(0.19, bridge.getStress(), .05);

        bridge.setStats(createJvbStats(5 /* numOf local senders */, 5 /* numOf local receivers */));
        Assert.assertEquals(0.35, bridge.getStress(), .05);

        bridge.setStats(createJvbStats(10 /* numOf local senders */, 5 /* numOf local receivers */));
        Assert.assertEquals(0.54, bridge.getStress(), .05);

        bridge.setStats(createJvbStats(10 /* numOf local senders */, 10 /* numOf local receivers */));
        Assert.assertEquals(0.69, bridge.getStress(), .05);

        bridge.setStats(createJvbStats(15 /* numOf local senders */, 10 /* numOf local receivers */));
        Assert.assertEquals(.89, bridge.getStress(), .05);

        bridge.setStats(createJvbStats(20 /* numOf local senders */, 5 /* numOf local receivers */));
        Assert.assertEquals(.94, bridge.getStress(), .05);

        bridge.setStats(createJvbStats(5 /* numOf local senders */, 20 /* numOf local receivers */));
        Assert.assertEquals(.78, bridge.getStress(), .05);
    }

    private ColibriStatsExtension createJvbStats(int numberOfLocalSenders, int numberOfLocalReceivers)
    {
        MaxPacketRateCalculator maxPacketRateCalculator = new MaxPacketRateCalculator(
            4 /* numberOfConferenceBridges */,
            20 /* numberOfGlobalSenders */,
            2 /* numberOfSpeakers */
        );

        int maxDownload = maxPacketRateCalculator.computeMaxDownload(numberOfLocalSenders)
            , maxUpload = maxPacketRateCalculator.computeMaxUpload(numberOfLocalSenders, numberOfLocalReceivers);

        ColibriStatsExtension statsExtension = new ColibriStatsExtension();

        statsExtension.addStat(
            new ColibriStatsExtension.Stat(
                PACKET_RATE_DOWNLOAD, maxDownload));
        statsExtension.addStat(
            new ColibriStatsExtension.Stat(
                PACKET_RATE_UPLOAD, maxUpload));

        return statsExtension;
    }
}