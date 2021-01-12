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

import mock.*;
import net.java.sip.communicator.service.protocol.*;
import org.jitsi.impl.protocol.xmpp.*;
import org.jitsi.protocol.xmpp.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;
import org.jxmpp.stringprep.*;

import java.util.*;

/**
 * @author Pawel Domas
 */
public class MockMultiUserChatOpSet
    implements OperationSetMultiUserChat2
{
    private static final Map<String, MockMucShare> mucDomainSharing = new HashMap<>();

    private final MockProtocolProvider protocolProviderService;

    private final Map<EntityBareJid, MockMultiUserChat> chatRooms
        = new HashMap<>();

    private static EntityBareJid fixRoomName(String room)
    {
        try
        {
            return JidCreate.entityBareFrom(room);
        }
        catch (XmppStringprepException e)
        {
            throw new RuntimeException(e);
        }
    }

    public MockMultiUserChatOpSet(MockProtocolProvider protocolProviderService)
    {
        this.protocolProviderService = protocolProviderService;
    }

    @Override
    public ChatRoom2 createChatRoom(String roomName)
        throws OperationFailedException
    {
        EntityBareJid roomNameJid = fixRoomName(roomName);

        synchronized (chatRooms)
        {
            if (chatRooms.containsKey(roomNameJid))
            {
                throw new OperationFailedException(
                    "Room " + roomName + " already exists.",
                    OperationFailedException.GENERAL_ERROR);
            }

            MockMultiUserChat chatRoom
                = new MockMultiUserChat(
                    roomNameJid,
                    protocolProviderService,
                    protocolProviderService.config.getUsername().toString());

            chatRooms.put(roomNameJid, chatRoom);

            MockMucShare sharedDomain = mucDomainSharing.get(roomName);
            if (sharedDomain == null)
            {
                sharedDomain = new MockMucShare(roomNameJid);

                mucDomainSharing.put(roomName, sharedDomain);
            }

            sharedDomain.nextRoomCreated(chatRoom);

            return chatRoom;
        }
    }

    @Override
    public ChatRoom2 findRoom(String roomName)
        throws OperationFailedException
    {
        // MUC room names are case insensitive
        EntityBareJid roomNameJid = fixRoomName(roomName);

        synchronized (chatRooms)
        {
            if (!chatRooms.containsKey(roomNameJid))
            {
                ChatRoom room = createChatRoom(roomName);
                chatRooms.put(roomNameJid, (MockMultiUserChat) room);
            }
            return chatRooms.get(roomNameJid);
        }
    }

    static public void cleanMucSharing()
    {
        mucDomainSharing.clear();
    }
}
