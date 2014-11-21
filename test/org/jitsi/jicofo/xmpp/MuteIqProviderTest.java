/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.jicofo.xmpp;

import org.jitsi.impl.protocol.xmpp.extensions.*;

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
