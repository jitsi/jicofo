/*
 * Jitsi, the OpenSource Java VoIP and Instant Messaging client.
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

import java.util.*;

import net.java.sip.communicator.service.protocol.*;

/**
 * Allows creating, configuring, joining and administering of individual
 * text-based conference rooms.
 *
 * @author Emil Ivov
 */
public interface OperationSetMultiUserChat2
{
    /**
     * Creates a room with the named <tt>roomName</tt> and according to the
     * specified <tt>roomProperties</tt> on the server that this protocol
     * provider is currently connected to. When the method returns the room the
     * local user will not have joined it and thus will not receive messages on
     * it until the <tt>ChatRoom.join()</tt> method is called.
     * <p>
     *
     * @param roomName
     *            the name of the <tt>ChatRoom</tt> to create.
     * @param roomProperties
     *            properties specifying how the room should be created;
     *            <tt>null</tt> for no properties just like an empty
     *            <code>Map</code>
     * @throws OperationFailedException
     *             if the room couldn't be created for some reason (e.g. room
     *             already exists; user already joined to an existent room or
     *             user has no permissions to create a chat room).
     * @throws OperationNotSupportedException
     *             if chat room creation is not supported by this server
     *
     * @return the newly created <tt>ChatRoom</tt> named <tt>roomName</tt>.
     */
    ChatRoom createChatRoom(String roomName, Map<String, Object> roomProperties)
        throws OperationFailedException,
               OperationNotSupportedException;

    /**
     * Returns a reference to a chatRoom named <tt>roomName</tt> or null
     * if no room with the given name exist on the server.
     * <p>
     * @param roomName the name of the <tt>ChatRoom</tt> that we're looking for.
     * @return the <tt>ChatRoom</tt> named <tt>roomName</tt> if it exists, null
     * otherwise.
     *
     * @throws OperationFailedException if an error occurs while trying to
     * discover the room on the server.
     * @throws OperationNotSupportedException if the server does not support
     * multi-user chat
     */
    ChatRoom findRoom(String roomName)
        throws OperationFailedException, OperationNotSupportedException;
}
