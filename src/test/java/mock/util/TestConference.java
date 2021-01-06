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

import mock.xmpp.*;
import org.jitsi.jicofo.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;
import org.osgi.framework.*;

import java.util.*;

import static org.jitsi.jicofo.util.ServiceUtilsKt.getService;

/**
 *
 */
public class TestConference
{
    private final BundleContext bc;

    private EntityBareJid roomName;

    private Jid mockBridgeJid;

    private MockProtocolProvider focusProtocolProvider;

    public JitsiMeetConferenceImpl conference;

    private MockVideobridge mockBridge;


    static public TestConference allocate(
        BundleContext ctx, String serverName, EntityBareJid roomName)
        throws Exception
    {
        TestConference newConf = new TestConference(ctx);

        newConf.createJvbAndConference(serverName, roomName);

        return newConf;
    }

    private JitsiMeetServices getJitsiMeetServices()
    {
        return getService(bc, JitsiMeetServices.class);
    }

    private FocusManager getFocusManager()
    {
        return getService(bc, FocusManager.class);
    }

    public TestConference(BundleContext osgi)
    {
        this.bc = osgi;
    }

    private void createJvbAndConference(String serverName, EntityBareJid roomName)
        throws Exception
    {
        this.mockBridgeJid = JidCreate.from("mockjvb." + serverName);

        MockVideobridge mockBridge = new MockVideobridge(new MockXmppConnection(mockBridgeJid), mockBridgeJid);

        mockBridge.start(bc);

        getJitsiMeetServices().getBridgeSelector().addJvbAddress(mockBridgeJid);

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
