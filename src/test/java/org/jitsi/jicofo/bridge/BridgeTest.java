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
    MaxPacketRateCalculator packetRateCalculator = new MaxPacketRateCalculator(
        4 /* numberOfConferenceBridges */,
        20 /* numberOfGlobalSenders */,
        2 /* numberOfSpeakers */
    );

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

        bridge.setStats(createJvbStats(0 /* numberOfLocalSenders */, 0 /* numberOfLocalReceivers */));
        Assert.assertEquals(0.0, bridge.getStress(), .05);

        bridge.setStats(createJvbStats(5 /* numberOfLocalSenders */, 0 /* numberOfLocalReceivers */));
        Assert.assertEquals(0.19, bridge.getStress(), .05);

        bridge.setStats(createJvbStats(5 /* numberOfLocalSenders */, 5 /* numberOfLocalReceivers */));
        Assert.assertEquals(0.35, bridge.getStress(), .05);

        bridge.setStats(createJvbStats(10 /* numberOfLocalSenders */, 5 /* numberOfLocalReceivers */));
        Assert.assertEquals(0.54, bridge.getStress(), .05);

        bridge.setStats(createJvbStats(10 /* numberOfLocalSenders */, 10 /* numberOfLocalReceivers */));
        Assert.assertEquals(0.69, bridge.getStress(), .05);

        bridge.setStats(createJvbStats(15 /* numberOfLocalSenders */, 10 /* numberOfLocalReceivers */));
        Assert.assertEquals(.94, bridge.getStress(), .05);

        bridge.setStats(createJvbStats(20 /* numberOfLocalSenders */, 5 /* numberOfLocalReceivers */));
        Assert.assertEquals(1.0, bridge.getStress(), .05);

        bridge.setStats(createJvbStats(5 /* numberOfLocalSenders */, 20 /* numberOfLocalReceivers */));
        Assert.assertEquals(.83, bridge.getStress(), .05);
    }

    private ColibriStatsExtension createJvbStats(int numberOfLocalSenders, int numberOfLocalReceivers)
    {
        int maxDownload = packetRateCalculator.computeIngressPacketRatePps(numberOfLocalSenders)
            , maxUpload = packetRateCalculator.computeEgressPacketRatePps(numberOfLocalSenders, numberOfLocalReceivers);

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