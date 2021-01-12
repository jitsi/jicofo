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
package org.jitsi.impl.protocol.xmpp;

import net.java.sip.communicator.service.protocol.*;

import org.jitsi.protocol.xmpp.*;
import org.jivesoftware.smack.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;
import org.jxmpp.stringprep.*;

import java.util.*;

/**
 * Multi user chat implementation stripped to the minimum required by the focus
 * of Jitsi Meet conference. Uses Smack backend.
 *
 * @author Pawel Domas
 */
public class OperationSetMultiUserChatImpl
    implements OperationSetMultiUserChat2
{
    /**
     * Parent protocol provider.
     */
    private final XmppProtocolProvider protocolProvider;

    /**
     * The map of active chat rooms mapped by their names.
     */
    private final Map<String, ChatRoomImpl> rooms = new HashMap<>();

    /**
     * Creates new instance of {@link OperationSetMultiUserChatImpl}.
     *
     * @param protocolProvider parent protocol provider service.
     */
    OperationSetMultiUserChatImpl(XmppProtocolProvider protocolProvider)
    {
        this.protocolProvider = protocolProvider;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ChatRoom2 createChatRoom(String roomName)
        throws OperationFailedException
    {
        EntityBareJid roomJid;
        try
        {
            roomJid = JidCreate.entityBareFrom(roomName);
        }
        catch (XmppStringprepException e)
        {
            throw new OperationFailedException(
                    "Invalid room name",
                    OperationFailedException.ILLEGAL_ARGUMENT,
                    e);
        }

        synchronized (rooms)
        {
            if (rooms.containsKey(roomName))
            {
                throw new OperationFailedException(
                    "Room '" + roomName + "' exists",
                    OperationFailedException.GENERAL_ERROR);
            }

            ChatRoomImpl newRoom = new ChatRoomImpl(this, roomJid);

            rooms.put(newRoom.getName(), newRoom);

            return newRoom;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ChatRoom2 findRoom(String roomName)
        throws OperationFailedException
    {
        roomName = roomName.toLowerCase();

        synchronized (rooms)
        {
            ChatRoom2 room = rooms.get(roomName);

            if (room == null)
            {
                room = createChatRoom(roomName);
            }
            return room;
        }
    }

    /**
     * Returns Smack connection object used by parent protocol provider service.
     */
    public XMPPConnection getConnection()
    {
        return protocolProvider.getXmppConnectionRaw();
    }

    /**
     * Returns parent protocol provider service.
     */
    public XmppProtocolProvider getProtocolProvider()
    {
        return protocolProvider;
    }

    public void removeRoom(ChatRoomImpl chatRoom)
    {
        synchronized (rooms)
        {
            rooms.remove(chatRoom.getName());
        }
    }
}
