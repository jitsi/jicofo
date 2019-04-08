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
import org.jitsi.xmpp.extensions.jingle.*;
import org.jivesoftware.smack.packet.*;
import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;
import org.jxmpp.jid.impl.*;
import org.jxmpp.stringprep.*;

import static org.junit.Assert.assertEquals;

@RunWith(JUnit4.class)
public class ConnectionMockTest
{
    private XmppPeer peerA;
    private XmppPeer peerB;
    private XmppPeer peerC;

    @Before
    public void setup()
            throws Exception
    {
        peerA = new XmppPeer("A");
        peerB = new XmppPeer("B");
        peerC = new XmppPeer("C");

        peerA.start();
        peerB.start();
        peerC.start();
    }

    @After
    public void tearDown()
    {
        peerA.stop();
        peerB.stop();
        peerC.stop();
    }

    @Test
    public void testXmpConnectionIqGet()
            throws InterruptedException, XmppStringprepException
    {
        peerA.getConnection().sendStanza(getIq("B"));
        peerA.getConnection().sendStanza(getIq("C"));
        peerB.getConnection().sendStanza(getIq("A"));

        Thread.sleep(500);

        assertEquals(1, peerA.getIqCount());
        assertEquals(1, peerB.getIqCount());
        assertEquals(1, peerC.getIqCount());

        assertEquals("a", peerA.getIq(0).getTo().toString());
        assertEquals("b", peerB.getIq(0).getTo().toString());
        assertEquals("c", peerC.getIq(0).getTo().toString());
    }

    private JingleIQ getIq(String to)
            throws XmppStringprepException
    {
        JingleIQ jingle = new JingleIQ(JingleAction.SESSION_INFO, "123");
        jingle.setType(IQ.Type.get);
        jingle.setTo(JidCreate.from(to));

        return jingle;
    }
}
