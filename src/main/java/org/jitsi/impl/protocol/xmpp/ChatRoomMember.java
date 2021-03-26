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
import org.jivesoftware.smack.packet.*;
import org.jxmpp.jid.*;

/**
 * This interface represents chat room participants. Instances are retrieved
 * through implementations of the <tt>ChatRoom</tt> interface and offer methods
 * that allow querying member properties, such as, moderation permissions,
 * associated chat room and other.
 *
 * @author Emil Ivov
 * @author Boris Grozev
 */
public interface ChatRoomMember
{
    /**
     * Returns the name of this member as it is known in its containing
     * chatroom (aka a nickname). The name returned by this method, may
     * sometimes match the string returned by getContactID() which is actually
     * the address of  a contact in the realm of the corresponding protocol.
     *
     * @return the name of this member as it is known in the containing chat
     * room (aka a nickname).
     */
    String getName();

    /**
     * Returns the role of this chat room member in its containing room.
     *
     * @return a <tt>ChatRoomMemberRole</tt> instance indicating the role
     * the this member in its containing chat room.
     */
    MemberRole getRole();

    /**
     * Sets the role of this chat room member in its containing room.
     *
     * @param role <tt>ChatRoomMemberRole</tt> instance indicating the role
     * to set for this member in its containing chat room.
     */
    void setRole(MemberRole role);

    /**
     * Returns the JID of the user (outside the MUC), i.e. the "real" JID.
     */
    Jid getJid();

    /**
     * Returns the user's MUC address.
     */
    EntityFullJid getOccupantJid();

    /**
     * Returns number based on the order of joining of the members in the room.
     * @return number based on the order of joining of the members in the room.
     * TODO: only needed because of startMuted, remove once startMuted is client side.
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
     * Tells if this <tt>XmppChatMember</tt> is a robot(SIP gateway,
     * recorder component etc.).
     * @return <tt>true</tt> if this MUC member is a robot or <tt>false</tt>
     * otherwise.
     */
    boolean isRobot();

    /**
     * Gets the region (e.g. "us-east") of this {@link ChatRoomMember}.
     */
    String getRegion();

    /**
     * Gets the statistics id if any.
     * @return the statistics ID for this member.
     */
    String getStatsId();
}
