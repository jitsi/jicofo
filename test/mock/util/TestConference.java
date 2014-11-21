package mock.util;

import mock.*;
import mock.jvb.*;
import mock.muc.*;

import net.java.sip.communicator.service.protocol.*;
import net.java.sip.communicator.util.*;

import org.jitsi.jicofo.*;

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

        focusManager.conferenceRequest(roomName);

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
}
