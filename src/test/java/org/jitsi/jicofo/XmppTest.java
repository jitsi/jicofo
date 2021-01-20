/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2015-Present 8x8, Inc.
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

import org.jitsi.xmpp.extensions.jitsimeet.*;
import org.jitsi.jicofo.xmpp.*;

import org.jivesoftware.smack.packet.IQ;
import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;

import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 *
 */
@RunWith(JUnit4.class)
public class XmppTest
{
    static OSGiHandler osgi = OSGiHandler.getInstance();

    @BeforeClass
    public static void setUpClass()
        throws Exception
    {
        osgi.init();
    }

    @AfterClass
    public static void tearDownClass()
        throws Exception
    {
        osgi.shutdown();
    }

    @Test
    public void testAllocateConference()
        throws Exception
    {
        EntityBareJid roomName = JidCreate.entityBareFrom("testRoom@example.com");

        IqHandler iqHandler = osgi.jicofoServices.getIqHandler();

        ConferenceIq conferenceIq = new ConferenceIq();

        conferenceIq.setFrom("from@example.com");
        conferenceIq.setRoom(roomName);

        IQ result = iqHandler.handleIq(conferenceIq);
        assertNotNull(result);
        assertTrue(result instanceof ConferenceIq);
    }
}
