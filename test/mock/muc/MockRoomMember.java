/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package mock.muc;

import mock.xmpp.*;
import net.java.sip.communicator.service.protocol.*;
import net.java.sip.communicator.service.protocol.globalstatus.*;
import org.jitsi.jicofo.util.*;
import org.jitsi.protocol.xmpp.*;

import java.util.*;

/**
 * @author Pawel Domas
 */
public class MockRoomMember
    implements XmppChatMember
{
    private final String name;

    private final String address;

    private final MockMultiUserChat room;

    private ChatRoomMemberRole role = ChatRoomMemberRole.MEMBER;

    MockRoomMember(String address, MockMultiUserChat chatRoom)
    {
        this.address = address;
        this.name = address.substring(address.lastIndexOf("/")+1);
        this.room = chatRoom;
    }

    public void setupFeatures(boolean useBundle)
    {
        OperationSetSimpleCaps caps
                = room.getParentProvider()
                .getOperationSet(OperationSetSimpleCaps.class);

        MockSetSimpleCapsOpSet mockCaps = (MockSetSimpleCapsOpSet) caps;

        List<String> features = DiscoveryUtil.getDefaultParticipantFeatureSet();
        if (useBundle)
        {
            features.add("urn:ietf:rfc:5761"/* rtcp-mux */);
            features.add("urn:ietf:rfc:5888"/* bundle */);
        }

        MockCapsNode myNode
            = new MockCapsNode(
                address, features.toArray(new String[features.size()]));

        mockCaps.addChildNode(myNode);
    }

    @Override
    public ChatRoom getChatRoom()
    {
        return room;
    }

    @Override
    public ProtocolProviderService getProtocolProvider()
    {
        return room.getParentProvider();
    }

    @Override
    public String getContactAddress()
    {
        return address;
    }

    @Override
    public String getName()
    {
        return name;
    }

    @Override
    public byte[] getAvatar()
    {
        return new byte[0];
    }

    @Override
    public Contact getContact()
    {
        return null;
    }

    @Override
    public ChatRoomMemberRole getRole()
    {
        return role;
    }

    public void leave()
    {
        room.mockLeave(getName());
    }

    @Override
    public void setRole(ChatRoomMemberRole role)
    {
        this.role = role;
    }

    @Override
    public PresenceStatus getPresenceStatus()
    {
        return GlobalStatusEnum.ONLINE;
    }

    @Override
    public String toString()
    {
        return "Member@" + hashCode() + "[" + name + "]";
    }

    @Override
    public String getJabberID()
    {
        return null;
    }
}
