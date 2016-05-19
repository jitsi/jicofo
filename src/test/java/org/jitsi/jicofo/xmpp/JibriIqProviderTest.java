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

import net.java.sip.communicator.impl.protocol.jabber.extensions.jibri.*;

import org.jitsi.xmpp.util.*;

import org.jivesoftware.smack.packet.*;

import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Few basic tests for parsing JibriIQ
 *
 * @author Pawel Domas
 */
@RunWith(JUnit4.class)
public class JibriIqProviderTest
{
    @Test
    public void testParseIQ()
        throws Exception
    {
        JibriIqProvider provider = new JibriIqProvider();

        // JibriIq
        String iqXml =
            "<iq to='t' from='f'>" +
                "<jibri xmlns='http://jitsi.org/protocol/jibri'" +
                "       status='busy' action='stop'/>" +
                "</iq>";

        JibriIq jibriIq = (JibriIq) IQUtils.parse(iqXml, provider);

        assertNotNull(jibriIq);

        assertEquals(JibriIq.Status.BUSY, jibriIq.getStatus());
        assertEquals(JibriIq.Action.STOP, jibriIq.getAction());

        assertNull(jibriIq.getError());
    }

    @Test
    public void testParseError()
        throws Exception
    {
        JibriIqProvider provider = new JibriIqProvider();

        // JibriIq
        String iqXml =
            "<iq to='t' from='f'>" +
                "<jibri xmlns='http://jitsi.org/protocol/jibri'" +
                "       status='failed'>" +
                "<error xmlns='urn:ietf:params:xml:ns:xmpp-stanzas' " +
                "       type='wait' code='504'>" +
                "<remote-server-timeout" +
                "       xmlns='urn:ietf:params:xml:ns:xmpp-stanzas'/>" +
                "<text>Youtube request timeout</text>" +
                "</error>" +
                "<jibri/>" +
                "</iq>";

        JibriIq jibriIq = (JibriIq) IQUtils.parse(iqXml, provider);

        assertNotNull(jibriIq);

        assertEquals(JibriIq.Status.FAILED, jibriIq.getStatus());

        XMPPError error = jibriIq.getError();
        assertNotNull(error);

        assertEquals(XMPPError.Type.WAIT, error.getType());

        assertEquals(504, error.getCode());

        assertEquals("Youtube request timeout", error.getMessage());

        String condition = error.getCondition();

        assertNotNull(condition);

        assertEquals(
                XMPPError.Condition.remote_server_timeout.toString(),
                condition);
    }
}
