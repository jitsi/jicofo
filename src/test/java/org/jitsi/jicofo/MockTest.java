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
package org.jitsi.jicofo;

import mock.xmpp.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;

import java.util.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 *
 */
@RunWith(JUnit4.class)
public class MockTest
{
    @Test
    public void testXmpConnection()
        throws InterruptedException
    {
        MockXmppConnection mockConnection
            = new MockXmppConnectionImpl();

        XmppPeer peerA = new XmppPeer("A", mockConnection);
        XmppPeer peerB = new XmppPeer("B", mockConnection);
        XmppPeer peerC = new XmppPeer("C", mockConnection);

        peerA.start();
        peerB.start();
        peerC.start();

        mockConnection.sendPacket(getPacket("A", "B", "AtoB"));
        mockConnection.sendPacket(getPacket("A", "C", "AtoC"));
        mockConnection.sendPacket(getPacket("B", "A", "BtoA"));

        Thread.sleep(500);

        peerA.stop();
        peerB.stop();
        peerC.stop();

        assertEquals(1, peerA.getPacketCount());
        assertEquals(1, peerB.getPacketCount());
        assertEquals(1, peerC.getPacketCount());

        peerA.getPacket(0).getTo().equals("A");
        peerB.getPacket(0).getTo().equals("B");
        peerC.getPacket(0).getTo().equals("C");
    }

    private JingleIQ getPacket(String from, String to, String payload)
    {
        JingleIQ jingle = new JingleIQ();

        jingle.setFrom(from);
        jingle.setTo(to);
        jingle.setProperty("value", payload);

        return jingle;
    }

    @Test
    public void testMockCaps()
    {
        MockSetSimpleCapsOpSet mockCaps = new MockSetSimpleCapsOpSet("root");

        MockCapsNode node = new MockCapsNode(
            "node1",
            new String[]{ "featureA", "featureB"});

        mockCaps.addChildNode(node);

        mockCaps.addChildNode(
            new MockCapsNode(
                "node2",
                new String[]{ "featureC"}));

        mockCaps.addChildNode(
            new MockCapsNode(
                "node3",
                new String[]{ "featureC"}));

        assertTrue(
            mockCaps.hasFeatureSupport(
                "node1",
                new String[]{ "featureA", "featureB"}));

        Set<String> nodes = mockCaps.getItems("root");
        assertEquals(3, nodes.size());
    }
}
