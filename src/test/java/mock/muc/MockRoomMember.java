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

    @Override
    public int getJoinOrderNumber()
    {
        //FIXME: implement in order to test start muted feature
        return 0;
    }
}
