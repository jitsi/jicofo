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

import org.custommonkey.xmlunit.*;
import org.jitsi.xmpp.extensions.jitsimeet.*;
import org.jitsi.xmpp.util.*;
import org.jivesoftware.smack.packet.*;
import org.junit.*;
import org.jxmpp.jid.impl.*;
import org.jxmpp.stringprep.*;
import org.xml.sax.*;

import java.io.*;
import java.net.*;
import java.util.*;

import static org.custommonkey.xmlunit.XMLAssert.assertXMLEqual;
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
    public void testParseConferenceIq()
        throws Exception {
        // ConferenceIq
        String iqXml =
                "<iq to='t' from='f' type='set'>" +
                        "<conference xmlns='http://jitsi.org/protocol/focus'" +
                        " room='someroom@example.com' ready='true'" +
                        ">" +
                        "<property xmlns='http://jitsi.org/protocol/focus' " +
                        "name='name1' value='value1'/>" +
                        "<property name='name2' value='value2'/>" +
                        "</conference>" +
                        "</iq>";

        ConferenceIqProvider provider = new ConferenceIqProvider();
        ConferenceIq conference
                = IQUtils.parse(iqXml, provider);

        assertEquals("someroom@example.com",
                conference.getRoom().toString());
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
    public void testParseLoginUrlIq()
            throws Exception
    {
        String originalUrl = "somesdf23454$%12!://";
        String encodedUrl = URLEncoder.encode(originalUrl, "UTF8");

        // AuthUrlIq
        String authUrlIqXml = "<iq to='to1' from='from3' type='result'>" +
                "<login-url xmlns='http://jitsi.org/protocol/focus'" +
                " url=\'" + encodedUrl
                + "\' room='someroom1234@example.com' />" +
                "</iq>";

        LoginUrlIq authUrlIq
                = IQUtils.parse(authUrlIqXml, new LoginUrlIqProvider());

        assertNotNull(authUrlIq);
        assertEquals("to1", authUrlIq.getTo().toString());
        assertEquals("from3", authUrlIq.getFrom().toString());
        assertEquals(IQ.Type.result, authUrlIq.getType());
        assertEquals(originalUrl, authUrlIq.getUrl());
        assertEquals("someroom1234@example.com",
                authUrlIq.getRoom().toString());
    }

    @Test
    public void testConferenceIqToXml()
            throws IOException, SAXException
    {
        ConferenceIq conferenceIq = new ConferenceIq();

        conferenceIq.setStanzaId("123xyz");
        conferenceIq.setTo(JidCreate.from("toJid@example.com"));
        conferenceIq.setFrom(JidCreate.from("fromJid@example.com"));

        conferenceIq.setRoom(JidCreate.entityBareFrom(
                "testroom1234@example.com"));
        conferenceIq.setReady(false);
        conferenceIq.addProperty(
                new ConferenceIq.Property("prop1", "some1"));
        conferenceIq.addProperty(
                new ConferenceIq.Property("name2", "xyz2"));

        assertXMLEqual(new Diff("<iq to='tojid@example.com' " +
                        "from='fromjid@example.com' " +
                        "id='123xyz' " +
                        "type='get'>" +
                        "<conference " +
                        "xmlns='http://jitsi.org/protocol/focus' " +
                        "room='testroom1234@example.com' ready='false'" +
                        ">" +
                        "<property xmlns='http://jitsi.org/protocol/focus' name='prop1' value='some1'/>" +
                        "<property xmlns='http://jitsi.org/protocol/focus' name='name2' value='xyz2'/>" +
                        "</conference>" +
                        "</iq>",
                conferenceIq.toXML().toString()), true);
    }

    @Test
    public void testLoginUrlIqToXml()
            throws UnsupportedEncodingException, XmppStringprepException
    {
        LoginUrlIq authUrlIQ = new LoginUrlIq();

        authUrlIQ.setStanzaId("1df:234sadf");
        authUrlIQ.setTo(JidCreate.from("to657@example.com"));
        authUrlIQ.setFrom(JidCreate.from("23from2134#@1"));
        authUrlIQ.setType(IQ.Type.result);

        authUrlIQ.setUrl("url://dsf78645!!@3fsd&");
        authUrlIQ.setRoom(JidCreate.entityBareFrom("room@sdaf.dsf.dsf"));

        String encodedUrl = URLEncoder.encode(authUrlIQ.getUrl(), "UTF8");

        assertEquals("<iq to='to657@example.com' " +
                "from='23from2134#@1' id='1df:234sadf' " +
                        "type='result'>" +
                        "<login-url " +
                        "xmlns='http://jitsi.org/protocol/focus' " +
                        "url=\'" + encodedUrl + "\' " +
                        "room='room@sdaf.dsf.dsf'" +
                        "/>" +
                        "</iq>", authUrlIQ.toXML().toString());
    }
}
