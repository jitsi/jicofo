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
package mock.util;

import mock.*;

import mock.jvb.*;
import mock.muc.*;

import mock.xmpp.*;
import org.jitsi.jicofo.*;
import org.jitsi.osgi.*;

import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;
import org.osgi.framework.*;

import java.util.*;

/**
 *
 */
public class TestConference
{
    private final BundleContext bc;

    private EntityBareJid roomName;

    private final OSGIServiceRef<JitsiMeetServices> meetServicesRef;

    private Jid mockBridgeJid;

    private final OSGIServiceRef<FocusManager> focusManagerRef;

    private MockProtocolProvider focusProtocolProvider;

    public JitsiMeetConferenceImpl conference;

    private MockVideobridge mockBridge;

    private MockMultiUserChat chat;

    static public TestConference allocate(
        BundleContext ctx, String serverName, EntityBareJid roomName)
        throws Exception
    {
        TestConference newConf = new TestConference(ctx);

        newConf.createJvbAndConference(serverName, roomName);

        return newConf;
    }

    static public TestConference allocate(
        BundleContext ctx, EntityBareJid roomName,
        MockVideobridge mockBridge)
        throws Exception
    {
        TestConference newConf = new TestConference(ctx);

        newConf.createConferenceRoom(roomName, mockBridge);

        return newConf;
    }

    public TestConference(BundleContext osgi)
    {
        this.bc = osgi;
        this.meetServicesRef = new OSGIServiceRef<>(osgi, JitsiMeetServices.class);
        this.focusManagerRef = new OSGIServiceRef<>(osgi, FocusManager.class);
    }

    private void createJvbAndConference(String serverName, EntityBareJid roomName)
        throws Exception
    {
        this.mockBridgeJid = JidCreate.from("mockjvb." + serverName);

        MockVideobridge mockBridge = new MockVideobridge(new MockXmppConnection(mockBridgeJid), mockBridgeJid);

        mockBridge.start(bc);

        meetServicesRef.get().getBridgeSelector().addJvbAddress(mockBridgeJid);

        createConferenceRoom(roomName, mockBridge);
    }

    public void stop()
    {
        mockBridge.stop(bc);
    }

    private void createConferenceRoom(EntityBareJid roomName, MockVideobridge mockJvb)
        throws Exception
    {
        this.roomName = roomName;
        this.mockBridge = mockJvb;
        this.mockBridgeJid = mockJvb.getBridgeJid();

        HashMap<String,String> properties = new HashMap<>();

        focusManagerRef.get().conferenceRequest(roomName, properties);

        this.conference = focusManagerRef.get().getConference(roomName);

        MockMultiUserChatOpSet mucOpSet
            = getFocusProtocolProvider().getMockChatOpSet();

        this.chat = (MockMultiUserChat) mucOpSet.findRoom(roomName.toString());
    }

    public MockProtocolProvider getFocusProtocolProvider()
    {
        if (focusProtocolProvider == null)
        {
            focusProtocolProvider
                = (MockProtocolProvider) focusManagerRef
                        .get().getProtocolProvider();
        }
        return focusProtocolProvider;
    }

    public MockVideobridge getMockVideoBridge()
    {
        return mockBridge;
    }

    public MockParticipant addParticipant()
    {
        MockParticipant newParticipant
            = new MockParticipant(StringGenerator.nextRandomStr());

        newParticipant.join(chat);

        return newParticipant;
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
