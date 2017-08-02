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
import org.jxmpp.jid.Jid;
import org.jxmpp.jid.impl.*;
import org.jxmpp.stringprep.*;

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
            throws InterruptedException, XmppStringprepException
    {
        MockXmppConnection mockConnection
            = new MockXmppConnectionImpl();

        XmppPeer peerA = new XmppPeer("A", mockConnection);
        XmppPeer peerB = new XmppPeer("B", mockConnection);
        XmppPeer peerC = new XmppPeer("C", mockConnection);

        peerA.start();
        peerB.start();
        peerC.start();

        mockConnection.sendPacket(getPacket("A", "B"));
        mockConnection.sendPacket(getPacket("A", "C"));
        mockConnection.sendPacket(getPacket("B", "A"));

        Thread.sleep(500);

        peerA.stop();
        peerB.stop();
        peerC.stop();

        assertEquals(1, peerA.getPacketCount());
        assertEquals(1, peerB.getPacketCount());
        assertEquals(1, peerC.getPacketCount());

        assertEquals("a", peerA.getPacket(0).getTo().toString());
        assertEquals("b", peerB.getPacket(0).getTo().toString());
        assertEquals("c", peerC.getPacket(0).getTo().toString());
    }

    private JingleIQ getPacket(String from, String to)
            throws XmppStringprepException
    {
        JingleIQ jingle = new JingleIQ(JingleAction.SESSION_INFO, "123");
        jingle.setFrom(JidCreate.from(from));
        jingle.setTo(JidCreate.from(to));

        return jingle;
    }

    @Test
    public void testMockCaps()
            throws XmppStringprepException
    {
        MockSetSimpleCapsOpSet mockCaps = new MockSetSimpleCapsOpSet(
                JidCreate.domainBareFrom("root"));

        MockCapsNode node = new MockCapsNode(
            JidCreate.from("node1"),
            new String[]{ "featureA", "featureB"});

        mockCaps.addChildNode(node);

        mockCaps.addChildNode(
            new MockCapsNode(
                    JidCreate.from("node2"),
                new String[]{ "featureC"}));

        mockCaps.addChildNode(
            new MockCapsNode(
                    JidCreate.from("node3"),
                new String[]{ "featureC"}));

        assertTrue(
            mockCaps.hasFeatureSupport(
                    JidCreate.from("node1"),
                new String[]{ "featureA", "featureB"}));

        Set<Jid> nodes = mockCaps.getItems(JidCreate.domainBareFrom("root"));
        assertEquals(3, nodes.size());
    }
}
