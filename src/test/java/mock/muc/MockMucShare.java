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

import net.java.sip.communicator.service.protocol.*;
import net.java.sip.communicator.service.protocol.event.*;
import org.jitsi.protocol.xmpp.*;
import org.jitsi.utils.logging.*;
import org.jxmpp.jid.*;

import java.util.*;

/**
 * The purpose of this class is to simulate mock room joined by all
 * <tt>net.java.sip.communicator.service.protocol.mock.MockProtocolProvider</tt>s
 * only if they share the same room name.
 *
 * @author Pawel Domas
 */
public class MockMucShare
    implements ChatRoomMemberPresenceListener
{
    private final static Logger logger = Logger.getLogger(MockMucShare.class);

    private final EntityBareJid roomName;

    private final List<MockMultiUserChat> groupedChats = new ArrayList<>();

    public MockMucShare(EntityBareJid roomName)
    {
        this.roomName = roomName;
    }

    public void nextRoomCreated(MockMultiUserChat chatRoom)
    {
        groupedChats.add(chatRoom);

        chatRoom.addMemberPresenceListener(this);

        // Copy existing members if any
        for (ChatRoomMember member : chatRoom.getMembers())
        {
            broadcastMemberJoined(chatRoom, member);
        }
    }

    @Override
    public void memberPresenceChanged(ChatRoomMemberPresenceChangeEvent evt)
    {
        String eventType = evt.getEventType();

        if (ChatRoomMemberPresenceChangeEvent.MEMBER_JOINED.equals(eventType))
        {
            broadcastMemberJoined(evt.getChatRoom(), evt.getChatRoomMember());
        }
        else if(
            ChatRoomMemberPresenceChangeEvent.MEMBER_KICKED.equals(eventType)
            || ChatRoomMemberPresenceChangeEvent.MEMBER_LEFT.equals(eventType)
            || ChatRoomMemberPresenceChangeEvent.MEMBER_QUIT.equals(eventType) )
        {
            broadcastMemberLeft(
                    evt.getChatRoom(),
                    (XmppChatMember)evt.getChatRoomMember());
        }
        else
        {
            logger.warn("Unsupported event type: " + eventType);
        }
    }

    private void broadcastMemberJoined(ChatRoom chatRoom,
                                       ChatRoomMember chatRoomMember)
    {
        for (MockMultiUserChat chatToNotify : groupedChats)
        {
            if (chatToNotify != chatRoom)
            {
                chatToNotify.removeMemberPresenceListener(this);

                chatToNotify.mockJoin((MockRoomMember) chatRoomMember);

                chatToNotify.addMemberPresenceListener(this);
            }
        }
    }

    private void broadcastMemberLeft(ChatRoom chatRoom,
                                     XmppChatMember chatRoomMember)
    {
        for (MockMultiUserChat chatToNotify : groupedChats)
        {
            if (chatToNotify != chatRoom)
            {
                MockRoomMember mockRoomMember
                    = (MockRoomMember) chatToNotify.findChatMember(
                            chatRoomMember.getOccupantJid());
                if (mockRoomMember != null)
                {
                    chatToNotify.mockLeave(mockRoomMember.getName());
                }
            }
        }
    }
}
