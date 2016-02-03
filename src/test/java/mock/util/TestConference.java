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

import net.java.sip.communicator.util.*;

import org.jitsi.jicofo.*;
import org.jitsi.videobridge.simulcast.*;

import java.util.*;

/**
 *
 */
public class TestConference
{
    private String serverName;

    private String roomName;

    private String mockBridgeJid;

    private FocusManager focusManager;

    private MockProtocolProvider focusProtocolProvider;

    private JitsiMeetConference conference;

    private JitsiMeetServices meetServices;

    private MockVideobridge mockBridge;

    private MockMultiUserChat chat;

    public void allocateMockConference(OSGiHandler osgi,
                                       String serverName, String roomName)
        throws Exception
    {
        this.serverName = serverName;
        this.roomName = roomName;
        this.mockBridgeJid = "mockjvb." + serverName;

        this.focusManager
            = ServiceUtils.getService(osgi.bc, FocusManager.class);

        this.meetServices
            = ServiceUtils.getService(FocusBundleActivator.bundleContext,
                                          JitsiMeetServices.class);

        meetServices.getBridgeSelector().addJvbAddress(mockBridgeJid);

        HashMap<String,String> properties = new HashMap<>();

        properties.put(JitsiMeetConfig.SIMULCAST_MODE_PNAME, "rewriting");

        focusManager.conferenceRequest(roomName, properties);

        this.conference = focusManager.getConference(roomName);

        this.focusProtocolProvider
            = (MockProtocolProvider) conference.getXmppProvider();

        this.mockBridge
            = new MockVideobridge(
                    osgi.bc,
                    focusProtocolProvider.getMockXmppConnection(),
                    mockBridgeJid);

        mockBridge.start();

        MockMultiUserChatOpSet mucOpSet
            = focusProtocolProvider.getMockChatOpSet();

        this.chat = (MockMultiUserChat) mucOpSet.findRoom(roomName);
    }

    public MockProtocolProvider getFocusProtocolProvider()
    {
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
        List<SimulcastStream> layers
            = mockBridge.getSimulcastLayers(conferenceId, videoChannelId);

        long[] ssrcs = new long[layers.size()];
        int idx = 0;
        for (SimulcastStream layer : layers)
        {
            ssrcs[idx++] = layer.getPrimarySSRC();
        }
        return ssrcs;
    }

    public int getParticipantCount()
    {
        return conference.getParticipantCount();
    }
}
