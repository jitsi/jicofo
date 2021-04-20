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
package mock.muc;

import org.jitsi.impl.protocol.xmpp.*;

import java.util.*;

import static org.jitsi.impl.protocol.xmpp.ChatRoomMemberPresenceChangeEvent.*;

/**
 * The purpose of this class is to simulate mock room joined by all {@link mock.MockXmppProvider}s only if they share
 * the same room name.
 *
 * @author Pawel Domas
 */
public class MockMucShare
{
    private final List<MockChatRoom> groupedChats = new ArrayList<>();

    public void nextRoomCreated(MockChatRoom chatRoom)
    {
        groupedChats.add(chatRoom);

        Listener listener = new Listener(chatRoom);
        chatRoom.addMemberPresenceListener(listener);

        // Copy existing members if any
        for (ChatRoomMember member : chatRoom.getMembers())
        {
            broadcastMemberJoined(chatRoom, listener, member);
        }
    }

    private void broadcastMemberJoined(ChatRoom chatRoom, Listener listener, ChatRoomMember chatRoomMember)
    {
        for (MockChatRoom chatToNotify : groupedChats)
        {
            if (chatToNotify != chatRoom)
            {
                // ???
                chatToNotify.removeMemberPresenceListener(listener);

                chatToNotify.mockJoin((MockRoomMember) chatRoomMember);

                chatToNotify.addMemberPresenceListener(listener);
            }
        }
    }

    private void broadcastMemberLeft(ChatRoom chatRoom, ChatRoomMember chatRoomMember)
    {
        for (MockChatRoom chatToNotify : groupedChats)
        {
            if (chatToNotify != chatRoom)
            {
                MockRoomMember mockRoomMember
                    = (MockRoomMember) chatToNotify.findChatMember(chatRoomMember.getOccupantJid());
                if (mockRoomMember != null)
                {
                    chatToNotify.mockLeave(mockRoomMember.getName());
                }
            }
        }
    }

    private class Listener
            implements ChatRoomMemberPresenceListener
    {
        private final ChatRoom chatRoom;
        private Listener(ChatRoom chatRoom)
        {
            this.chatRoom = chatRoom;
        }

        @Override
        public void memberPresenceChanged(ChatRoomMemberPresenceChangeEvent evt)
        {

            if (evt instanceof Joined)
            {
                broadcastMemberJoined(chatRoom, this, evt.getChatRoomMember());
            }
            else if(evt instanceof Kicked || evt instanceof Left)
            {
                broadcastMemberLeft(chatRoom, evt.getChatRoomMember());
            }
        }
    }

}
