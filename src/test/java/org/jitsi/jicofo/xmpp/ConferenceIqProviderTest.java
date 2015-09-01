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
import org.jivesoftware.smack.packet.*;
import org.junit.*;

import java.io.*;
import java.net.*;
import java.util.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

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
        // ConferenceIq
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

        String originalUrl = "somesdf23454$%12!://";
        String encodedUrl = URLEncoder.encode(originalUrl, "UTF8");

        // AuthUrlIq
        String authUrlIqXml = "<iq to='to1' from='from3' type='result'>" +
                "<login-url xmlns='http://jitsi.org/protocol/focus'" +
                " url=\'" + encodedUrl + "\' room='someroom1234' />" +
                "</iq>";

        LoginUrlIQ authUrlIq
                = (LoginUrlIQ) IQUtils.parse(authUrlIqXml, provider);

        assertNotNull(authUrlIq);
        assertEquals("to1", authUrlIq.getTo());
        assertEquals("from3", authUrlIq.getFrom());
        assertEquals(IQ.Type.RESULT, authUrlIq.getType());
        assertEquals(originalUrl, authUrlIq.getUrl());
        assertEquals("someroom1234", authUrlIq.getRoom());
    }

    @Test
    public void testToXml()
        throws UnsupportedEncodingException
    {
        ConferenceIq conferenceIq = new ConferenceIq();

        conferenceIq.setPacketID("123xyz");
        conferenceIq.setTo("toJid");
        conferenceIq.setFrom("fromJid");

        conferenceIq.setRoom("testroom1234");
        conferenceIq.setReady(false);
        conferenceIq.addProperty(
            new ConferenceIq.Property("prop1", "some1"));
        conferenceIq.addProperty(
            new ConferenceIq.Property("name2", "xyz2"));

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

        LoginUrlIQ authUrlIQ = new LoginUrlIQ();

        authUrlIQ.setPacketID("1df:234sadf");
        authUrlIQ.setTo("to657");
        authUrlIQ.setFrom("23from2134#@1");
        authUrlIQ.setType(IQ.Type.RESULT);

        authUrlIQ.setUrl("url://dsf78645!!@3fsd&");
        authUrlIQ.setRoom("room@sdaf.dsf.dsf");

        String encodedUrl = URLEncoder.encode(authUrlIQ.getUrl(), "UTF8");

        assertEquals("<iq id=\"1df:234sadf\" to=\"to657\" " +
                "from=\"23from2134#@1\" " +
                        "type=\"result\">" +
                        "<login-url " +
                        "xmlns='http://jitsi.org/protocol/focus' " +
                        "url=\'" + encodedUrl + "\' " +
                        "room='room@sdaf.dsf.dsf' " +
                        "/>" +
                        "</iq>", authUrlIQ.toXML());
    }
}
