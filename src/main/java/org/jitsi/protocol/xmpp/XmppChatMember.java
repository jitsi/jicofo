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
package org.jitsi.protocol.xmpp;

import net.java.sip.communicator.service.protocol.*;

import org.jivesoftware.smack.packet.*;

/**
 * XMPP extended interface of {@link ChatRoomMember}.
 *
 * @author Pawel Domas
 */
public interface XmppChatMember
    extends ChatRoomMember
{
    /**
     * Returns ths original user's connection Jabber ID and not the MUC address.
     */
    String getJabberID();

    /**
     * Returns number based on the order of joining of the members in the room.
     * @return number based on the order of joining of the members in the room.
     */
    int getJoinOrderNumber();

    /**
     * Obtains the last MUC <tt>Presence</tt> seen for this chat member.
     * @return the last {@link Presence} packet received for this
     *         <tt>XmppChatMember</tt> or <tt>null</tt> if we haven't received
     *         it yet.
     */
    Presence getPresence();

    /**
     * Check for video muted status.
     * @return <tt>true</tt> if the user has video muted, <tt>false</tt> when
     *         video is not muted or <tt>null</tt> if the status is unknown.
     */
    Boolean hasVideoMuted();

    /**
     * Tells if this <tt>XmppChatMember</tt> is a robot(SIP gateway,
     * recorder component etc.).
     * @return <tt>true</tt> if this MUC member is a robot or <tt>false</tt>
     * otherwise.
     */
    boolean isRobot();
}
