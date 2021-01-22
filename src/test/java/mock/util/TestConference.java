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

import mock.*;

import mock.jvb.*;

import mock.xmpp.*;
import org.jitsi.jicofo.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;

import java.util.*;

/**
 *
 */
public class TestConference
{
    private EntityBareJid roomName;

    private Jid mockBridgeJid;

    private MockProtocolProvider focusProtocolProvider;

    public JitsiMeetConferenceImpl conference;

    private MockVideobridge mockBridge;


    static public TestConference allocate(String serverName, EntityBareJid roomName)
        throws Exception
    {
        TestConference newConf = new TestConference();

        newConf.createJvbAndConference(serverName, roomName);

        return newConf;
    }

    private FocusManager getFocusManager()
    {
        return OSGiHandler.getInstance().jicofoServices.getFocusManager();
    }

    private void createJvbAndConference(String serverName, EntityBareJid roomName)
        throws Exception
    {
        this.mockBridgeJid = JidCreate.from("mockjvb." + serverName);

        MockVideobridge mockBridge = new MockVideobridge(new MockXmppConnection(mockBridgeJid), mockBridgeJid);

        mockBridge.start();

        OSGiHandler.getInstance().jicofoServices.getBridgeSelector().addJvbAddress(mockBridgeJid);

        createConferenceRoom(roomName, mockBridge);
    }

    public void stop()
    {
        mockBridge.stop();
    }

    private void createConferenceRoom(EntityBareJid roomName, MockVideobridge mockJvb)
        throws Exception
    {
        this.roomName = roomName;
        this.mockBridge = mockJvb;
        this.mockBridgeJid = mockJvb.getBridgeJid();

        HashMap<String,String> properties = new HashMap<>();

        getFocusManager().conferenceRequest(roomName, properties);

        this.conference = getFocusManager().getConference(roomName);
    }

    public MockProtocolProvider getFocusProtocolProvider()
    {
        if (focusProtocolProvider == null)
        {
            focusProtocolProvider = (MockProtocolProvider) getFocusManager().getProtocolProvider();
        }
        return focusProtocolProvider;
    }

    public MockVideobridge getMockVideoBridge()
    {
        return mockBridge;
    }

    public int getParticipantCount()
    {
        return conference.getParticipantCount();
    }

    public EntityBareJid getRoomName()
    {
        return roomName;
    }
}
