/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package mock.util;

import mock.*;
import mock.jvb.*;
import mock.muc.*;

import net.java.sip.communicator.service.protocol.*;
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
        throws OperationFailedException, OperationNotSupportedException
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

        focusManager.conferenceRequest(roomName, new HashMap<String, String>());

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
        SortedSet<SimulcastLayer> layers
            = mockBridge.getSimulcastLayers(conferenceId, videoChannelId);

        long[] ssrcs = new long[layers.size()];
        int idx = 0;
        for (SimulcastLayer layer : layers)
        {
            ssrcs[idx++] = layer.getPrimarySSRC();
        }
        return ssrcs;
    }
}
