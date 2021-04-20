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
package mock.util;

import mock.jvb.*;

import mock.muc.*;
import mock.xmpp.*;
import org.jitsi.jicofo.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;
import org.jxmpp.stringprep.*;

import java.util.*;

/**
 *
 */
public class TestConference
{
    private static final String DEFAULT_SERVER_NAME = "test-server";

    public JitsiMeetConferenceImpl conference;

    private MockVideobridge mockBridge;

    private final JicofoHarness harness;

    public TestConference(JicofoHarness harness, EntityBareJid roomName)
    {
        this(harness, roomName, DEFAULT_SERVER_NAME);
    }
    public TestConference(JicofoHarness harness, EntityBareJid roomName, String serverName)
    {
        this.harness = harness;
        createJvbAndConference(serverName, roomName);
    }

    private FocusManager getFocusManager()
    {
        return harness.jicofoServices.getFocusManager();
    }

    private void createJvbAndConference(String serverName, EntityBareJid roomName)
    {
        Jid bridgeJid;
        try
        {
            bridgeJid = JidCreate.from("mockjvb." + serverName);
        }
        catch (XmppStringprepException e)
        {
            throw new RuntimeException(e);
        }

        mockBridge = new MockVideobridge(new MockXmppConnection(bridgeJid));
        mockBridge.start();

        harness.jicofoServices.getBridgeSelector().addJvbAddress(bridgeJid);

        createConferenceRoom(roomName);
    }

    public void stop()
    {
        mockBridge.stop();
    }

    private void createConferenceRoom(EntityBareJid roomName)
    {
        HashMap<String,String> properties = new HashMap<>();

        try
        {
            getFocusManager().conferenceRequest(roomName, properties);
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }

        this.conference = getFocusManager().getConference(roomName);
    }

    public MockVideobridge getMockVideoBridge()
    {
        return mockBridge;
    }

    public int getParticipantCount()
    {
        return conference.getParticipantCount();
    }

    public MockChatRoom getChatRoom()
    {
        return (MockChatRoom) conference.getChatRoom();
    }
}
