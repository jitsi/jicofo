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

import org.jitsi.impl.neomedia.rtp.*;
import org.jitsi.jicofo.*;
import org.jitsi.osgi.*;

import org.osgi.framework.*;

import java.util.*;

/**
 *
 */
public class TestConference
{
    private final BundleContext bc;

    private String serverName;

    private String roomName;

    private final OSGIServiceRef<JitsiMeetServices> meetServicesRef;

    private String mockBridgeJid;

    private final OSGIServiceRef<FocusManager> focusManagerRef;

    private MockProtocolProvider focusProtocolProvider;

    private JitsiMeetConference conference;

    private MockVideobridge mockBridge;

    private MockMultiUserChat chat;

    static public TestConference allocate(
        BundleContext ctx, String serverName, String roomName)
        throws Exception
    {
        TestConference newConf = new TestConference(ctx);

        newConf.createJvbAndConference(serverName, roomName);

        return newConf;
    }

    static public TestConference allocate(
        BundleContext ctx, String serverName, String roomName,
        MockVideobridge mockBridge)
        throws Exception
    {
        TestConference newConf = new TestConference(ctx);

        newConf.createConferenceRoom(serverName, roomName, mockBridge);

        return newConf;
    }

    public TestConference(BundleContext osgi)
    {
        this.bc = osgi;
        this.meetServicesRef
            = new OSGIServiceRef<>(osgi, JitsiMeetServices.class);
        this.focusManagerRef = new OSGIServiceRef<>(osgi, FocusManager.class);
    }

    private void createJvbAndConference(String serverName, String roomName)
        throws Exception
    {
        this.mockBridgeJid = "mockjvb." + serverName;

        MockVideobridge mockBridge
            = new MockVideobridge(
                    getFocusProtocolProvider().getMockXmppConnection(),
                    mockBridgeJid);

        mockBridge.start(bc);

        meetServicesRef.get().getBridgeSelector().addJvbAddress(mockBridgeJid);

        createConferenceRoom(serverName, roomName, mockBridge);
    }

    public void stop()
        throws Exception
    {
        mockBridge.stop(bc);
    }

    private void createConferenceRoom(String serverName, String roomName,
                                      MockVideobridge mockJvb)
        throws Exception
    {
        this.serverName = serverName;
        this.roomName = roomName;
        this.mockBridge = mockJvb;
        this.mockBridgeJid = mockJvb.getBridgeJid();

        HashMap<String,String> properties = new HashMap<>();

        properties.put(JitsiMeetConfig.SIMULCAST_MODE_PNAME, "rewriting");

        focusManagerRef.get().conferenceRequest(roomName, properties);

        this.conference = focusManagerRef.get().getConference(roomName);

        MockMultiUserChatOpSet mucOpSet
            = getFocusProtocolProvider().getMockChatOpSet();

        this.chat = (MockMultiUserChat) mucOpSet.findRoom(roomName);
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

    public void addParticipant(MockParticipant user)
    {
        user.join(chat);
    }

    public MockParticipant addParticipant()
    {
        MockParticipant newParticipant
            = new MockParticipant(StringGenerator.nextRandomStr());

        newParticipant.join(chat);

        return newParticipant;
    }

    public ConferenceUtility getConferenceUtility()
    {
        return new ConferenceUtility(conference);
    }

    public long[] getSimulcastLayersSSRCs(String peerJid)
    {
        ConferenceUtility confUtility = getConferenceUtility();
        String conferenceId = confUtility.getJvbConferenceId();
        String videoChannelId
            = confUtility.getParticipantVideoChannelId(peerJid);
        List<RTPEncodingDesc> layers
            = mockBridge.getSimulcastLayers(conferenceId, videoChannelId);

        long[] ssrcs = new long[layers.size()];
        int idx = 0;
        for (RTPEncodingDesc layer : layers)
        {
            ssrcs[idx++] = layer.getPrimarySSRC();
        }
        return ssrcs;
    }

    public int getParticipantCount()
    {
        return conference.getParticipantCount();
    }

    public String getRoomName()
    {
        return roomName;
    }
}
