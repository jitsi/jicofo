/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.jicofo.xmpp;

import org.jitsi.impl.protocol.xmpp.extensions.*;
import org.junit.*;

import java.util.*;

import static org.junit.Assert.assertEquals;

/**
 * Tests for {@link ConferenceIqProvider}.
 *
 * @author Pawel Domas
 */
public class ConferenceIqProviderTest
{
    @Test
    public void testParseIq()
        throws Exception
    {
        String iqXml =
            "<iq to='t' from='f'>" +
                "<conference xmlns='http://jitsi.org/protocol/focus'" +
                " room='someroom' ready='true'" +
                " >" +
                "<property name='name1' value='value1' />" +
                "<property name='name2' value='value2' />" +
                "<conference/>" +
                "</iq>";

        ConferenceIqProvider provider = new ConferenceIqProvider();
        ConferenceIq conference
            = (ConferenceIq) IQUtils.parse(iqXml, provider);

        assertEquals("someroom", conference.getRoom());
        assertEquals(true, conference.isReady());

        List<ConferenceIq.Property> properties = conference.getProperties();
        assertEquals(2, properties.size());

        ConferenceIq.Property property1 = properties.get(0);
        assertEquals("name1", property1.getName());
        assertEquals("value1", property1.getValue());

        ConferenceIq.Property property2 = properties.get(1);
        assertEquals("name2", property2.getName());
        assertEquals("value2", property2.getValue());
    }

    @Test
    public void testToXml()
    {
        ConferenceIq conferenceIq = new ConferenceIq();

        conferenceIq.setPacketID("123xyz");
        conferenceIq.setTo("toJid");
        conferenceIq.setFrom("fromJid");

        conferenceIq.setRoom("testroom1234");
        conferenceIq.setReady(false);
        conferenceIq.addProperty(
            new ConferenceIq.Property("prop1","some1"));
        conferenceIq.addProperty(
            new ConferenceIq.Property("name2","xyz2"));

        assertEquals("<iq id=\"123xyz\" to=\"toJid\" from=\"fromJid\" " +
                         "type=\"get\">" +
                         "<conference " +
                         "xmlns='http://jitsi.org/protocol/focus' " +
                         "room='testroom1234' ready='false' " +
                         ">" +
                         "<property  name='prop1' value='some1'/>" +
                         "<property  name='name2' value='xyz2'/>" +
                         "</conference>" +
                         "</iq>",
                     conferenceIq.toXML());
    }
}
