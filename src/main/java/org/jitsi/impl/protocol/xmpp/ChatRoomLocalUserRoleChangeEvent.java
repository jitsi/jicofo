/*
 * Jitsi, the OpenSource Java VoIP and Instant Messaging client.
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
package org.jitsi.impl.protocol.xmpp;

import org.jitsi.jicofo.xmpp.muc.*;

/**
 * Dispatched to notify interested parties that a change in our role in the
 * source chat room has occurred. Changes may include us being granted admin
 * permissions, or other permissions.
 *
 * @see MemberRole
 *
 * @author Emil Ivov
 * @author Stephane Remy
 */
public class ChatRoomLocalUserRoleChangeEvent
{
    /**
     * The new role that local participant get.
     */
    private final MemberRole newRole;
    
    /**
     * If <tt>true</tt> this is initial role set.
     */
    private final boolean isInitial;

    /**
     * Creates a <tt>ChatRoomLocalUserRoleChangeEvent</tt> representing that a change in local participant role in the
     * source chat room has occurred.
     *
     * @param newRole the new role that local participant get
     * @param isInitial if <tt>true</tt> this is initial role set.
     */
    public ChatRoomLocalUserRoleChangeEvent(MemberRole newRole, boolean isInitial)
    {
        this.newRole = newRole;
        this.isInitial = isInitial;
    }

    /**
     * Returns the new role the local participant get.
     *
     * @return newRole the new role the local participant get
     */
    public MemberRole getNewRole()
    {
        return newRole;
    }

    /**
     * Returns <tt>true</tt> if this is initial role set.
     * @return <tt>true</tt> if this is initial role set.
     */
    public boolean isInitial()
    {
        return isInitial;
    }
}
