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

import org.jetbrains.annotations.*;
import org.jitsi.impl.protocol.xmpp.*;
import org.jitsi.jicofo.xmpp.muc.*;

import java.util.*;

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

        ChatRoomListener listener = new ChatRoomListenerImpl(chatRoom);
        chatRoom.addListener(listener);

        // Copy existing members if any
        for (ChatRoomMember member : chatRoom.getMembers())
        {
            broadcastMemberJoined(chatRoom, listener, member);
        }
    }

    private void broadcastMemberJoined(ChatRoom chatRoom, ChatRoomListener listener, ChatRoomMember chatRoomMember)
    {
        for (MockChatRoom chatToNotify : groupedChats)
        {
            if (chatToNotify != chatRoom)
            {
                // ???
                chatToNotify.removeListener(listener);

                chatToNotify.mockJoin((MockRoomMember) chatRoomMember);

                chatToNotify.addListener(listener);
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

    private class ChatRoomListenerImpl extends DefaultChatRoomListener
    {
        private final ChatRoom chatRoom;
        private ChatRoomListenerImpl(ChatRoom chatRoom)
        {
            this.chatRoom = chatRoom;
        }

        @Override
        public void memberJoined(@NotNull ChatRoomMember member)
        {
            broadcastMemberJoined(chatRoom, this, member);
        }

        @Override
        public void memberLeft(@NotNull ChatRoomMember member)
        {
            broadcastMemberLeft(chatRoom, member);
        }

        @Override
        public void memberKicked(@NotNull ChatRoomMember member)
        {
            broadcastMemberLeft(chatRoom, member);
        }
    }

}
