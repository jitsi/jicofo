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

import mock.*;

import org.jitsi.xmpp.extensions.jitsimeet.*;
import org.jitsi.jicofo.xmpp.*;
import org.jitsi.xmpp.util.*;

import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;

import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;
import org.xmpp.packet.*;

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
        EntityBareJid roomName = JidCreate.entityBareFrom(
                "testRoom@example.com");

        FocusComponent focusComponent
            = MockMainMethodActivator.getFocusComponent();

        ConferenceIq conferenceIq = new ConferenceIq();

        conferenceIq.setRoom(roomName);

        IQ result
            = focusComponent.handleIQSetImpl(
                IQUtils.convert(conferenceIq));

        assertNotNull(result);

        org.jivesoftware.smack.packet.IQ response =  IQUtils.convert(result);
        assertTrue(response instanceof ConferenceIq);


    }
}
