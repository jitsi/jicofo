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

import mock.*;
import org.jitsi.impl.protocol.xmpp.*;
import org.jxmpp.jid.*;

import java.util.*;

/**
 * @author Pawel Domas
 */
public class MockMultiUserChatOpSet
{
    private static final Map<String, MockMucShare> mucDomainSharing = new HashMap<>();

    private final MockXmppProvider xmppProvider;

    private final Map<EntityBareJid, MockChatRoom> chatRooms = new HashMap<>();

    public MockMultiUserChatOpSet(MockXmppProvider xmppProvider)
    {
        this.xmppProvider = xmppProvider;
    }

    public ChatRoom createChatRoom(EntityBareJid roomNameJid)
        throws XmppProvider.RoomExistsException
    {
        synchronized (chatRooms)
        {
            if (chatRooms.containsKey(roomNameJid))
            {
                throw new XmppProvider.RoomExistsException("Room " + roomNameJid + " already exists.");
            }

            MockChatRoom chatRoom
                = new MockChatRoom(
                    roomNameJid,
                    xmppProvider,
                    xmppProvider.config.getUsername().toString());

            chatRooms.put(roomNameJid, chatRoom);

            String roomName = roomNameJid.toString();
            MockMucShare sharedDomain = mucDomainSharing.get(roomName);
            if (sharedDomain == null)
            {
                sharedDomain = new MockMucShare();

                mucDomainSharing.put(roomName, sharedDomain);
            }

            sharedDomain.nextRoomCreated(chatRoom);

            return chatRoom;
        }
    }

    public ChatRoom findRoom(EntityBareJid roomJid)
        throws XmppProvider.RoomExistsException
    {
        synchronized (chatRooms)
        {
            if (!chatRooms.containsKey(roomJid))
            {
                ChatRoom room = createChatRoom(roomJid);
                chatRooms.put(roomJid, (MockChatRoom) room);
            }
            return chatRooms.get(roomJid);
        }
    }

    static public void cleanMucSharing()
    {
        mucDomainSharing.clear();
    }
}
