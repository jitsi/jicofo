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

import org.jitsi.impl.protocol.xmpp.extensions.*;

import org.jitsi.xmpp.util.*;
import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;

import static org.junit.Assert.assertEquals;

/**
 * Playground for testing {@link MuteIq} parsing.
 *
 * @author Pawel Domas
 */
@RunWith(JUnit4.class)
public class MuteIqProviderTest
{
    @Test
    public void testParseIq()
        throws Exception
    {
        String iqXml =
            "<iq to='t' from='f'>" +
                "<mute xmlns='http://jitsi.org/jitmeet/audio'" +
                     " jid='somejid' >" +
                "true" +
                "</mute>" +
                "</iq>";

        MuteIqProvider provider = new MuteIqProvider();
        MuteIq mute
            = (MuteIq) IQUtils.parse(iqXml, provider);

        assertEquals("f", mute.getFrom());
        assertEquals("t", mute.getTo());

        assertEquals("somejid", mute.getJid());

        assertEquals(true, mute.getMute());
    }

    @Test
    public void testToXml()
    {
        MuteIq muteIq = new MuteIq();

        muteIq.setPacketID("123xyz");
        muteIq.setTo("toJid");
        muteIq.setFrom("fromJid");

        muteIq.setJid("mucjid1234");
        muteIq.setMute(true);

        assertEquals("<iq id=\"123xyz\" to=\"toJid\" from=\"fromJid\" " +
                         "type=\"get\">" +
                         "<mute " +
                         "xmlns='http://jitsi.org/jitmeet/audio' " +
                         "jid='mucjid1234'" +
                         ">true</mute>" +
                         "</iq>",
                     muteIq.toXML());
    }
}
