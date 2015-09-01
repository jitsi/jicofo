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
package org.jitsi.jicofo.xmpp;

import net.java.sip.communicator.impl.protocol.jabber.extensions.jirecon.*;

import org.jitsi.xmpp.util.*;
import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;

import static org.junit.Assert.assertEquals;

/**
 *
 */
@RunWith(JUnit4.class)
public class JireconIqProviderTest
{
    @Test
    public void testParseIq()
        throws Exception
    {
        String iqXml =
            "<iq to='t' from='f'>" +
                "<recording xmlns='http://jitsi.org/protocol/jirecon'" +
                " action='start' status='initiating' mucjid='mucjid2133' " +
                " dst='/home/users/jirecon' rid='1232124' " +
                " />" +
            "</iq>";

        JireconIqProvider provider = new JireconIqProvider();
        JireconIq recording
            = (JireconIq) IQUtils.parse(iqXml, provider);

        assertEquals("f", recording.getFrom());
        assertEquals("t", recording.getTo());

        assertEquals(JireconIq.Action.START, recording.getAction());
        assertEquals(JireconIq.Status.INITIATING, recording.getStatus());

        assertEquals("mucjid2133", recording.getMucJid());
        assertEquals("/home/users/jirecon", recording.getOutput());
        assertEquals("1232124", recording.getRid());
    }

    @Test
    public void testToXml()
    {
        JireconIq recordingIq = new JireconIq();

        recordingIq.setPacketID("123xyz");
        recordingIq.setTo("toJid");
        recordingIq.setFrom("fromJid");

        recordingIq.setAction(JireconIq.Action.INFO);
        recordingIq.setMucJid("mucJid");
        recordingIq.setOutput("/home/users/jirecon/rec");
        recordingIq.setRid("2d32r43f34");

        assertEquals("<iq id=\"123xyz\" to=\"toJid\" from=\"fromJid\" " +
                         "type=\"get\">" +
                         "<recording " +
                         "xmlns='http://jitsi.org/protocol/jirecon' " +
                         "rid='2d32r43f34' action='info' mucjid='mucJid' " +
                         "dst='/home/users/jirecon/rec' />" +
                         "</iq>",
                     recordingIq.toXML());
    }
}
