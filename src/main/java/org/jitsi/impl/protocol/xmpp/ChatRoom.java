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

import org.jitsi.jicofo.*;
import org.jitsi.jicofo.xmpp.muc.*;
import org.jivesoftware.smack.*;
import org.jivesoftware.smack.packet.*;
import org.jxmpp.jid.*;

import java.util.*;

/**
 * Represents a chat channel/room/rendez-vous point/ where multiple chat users
 * could rally and communicate in a many-to-many fashion.
 *
 * @author Emil Ivov
 * @author Yana Stamcheva
 * @author Valentin Martinet
 */
public interface ChatRoom
{
    /**
     * Returns the name of this <tt>ChatRoom</tt>.
     *
     * @return a <tt>String</tt> containing the name of this <tt>ChatRoom</tt>.
     */
    String getName();

    /**
     * Joins this chat room with the nickname of the local user so that the
     * user would start receiving events and messages for it.
     */
    void join()
        throws SmackException, XMPPException, InterruptedException;

    /**
     * Returns true if the local user is currently in the multi user chat
     * (after calling one of the {@link #join()} methods).
     *
     * @return true if currently we're currently in this chat room and false
     * otherwise.
     */
    boolean isJoined();

    /**
     * Leave this chat room. Once this method is called, the user won't be
     * listed as a member of the chat room any more and no further chat events
     * will be delivered.
     */
    void leave();

    /**
     * Returns the local user's role in the context of this chat room or
     * <tt>null</tt> if not currently joined.
     *
     * @return the role currently being used by the local user in the context of
     * the chat room.
     */
    MemberRole getUserRole();

    /**
     * Adds a listener that will be notified of changes in our participation in
     * the room such as us being kicked, join, left...
     *
     * @param listener a member participation listener.
     */
    void addMemberPresenceListener(ChatRoomMemberPresenceListener listener);

    /**
     * Removes a listener that was being notified of changes in the
     * participation of other chat room participants such as users being kicked,
     * join, left.
     *
     * @param listener a member participation listener.
     */
    void removeMemberPresenceListener(ChatRoomMemberPresenceListener listener);

    /**
     * Adds a listener that will be notified of changes in our role in the room
     * such as us being granded operator.
     *
     * @param listener a local user role listener.
     */
    void addLocalUserRoleListener(ChatRoomLocalUserRoleListener listener);

    /**
     * Removes a listener that was being notified of changes in our role in this
     * chat room such as us being granded operator.
     *
     * @param listener a local user role listener.
     */
    void removeLocalUserRoleListener(ChatRoomLocalUserRoleListener listener);

    /**
     * Returns a <tt>List</tt> of <tt>ChatRoomMember</tt>s corresponding to all
     * members currently participating in this room.
     *
     * @return a <tt>List</tt> of <tt>ChatRoomMember</tt> instances
     * corresponding to all room members.
     */
    List<ChatRoomMember> getMembers();

    /**
     * Returns the number of participants that are currently in this chat room.
     * @return the number of <tt>Contact</tt>s, currently participating in
     * this room.
     */
    int getMembersCount();

    /**
    * Grants ownership privileges to another user. Room owners may grant
    * ownership privileges. Some room implementations will not allow to grant
    * ownership privileges to other users. An owner is allowed to change
    * defining room features as well as perform all administrative functions.
    *
    * @param address the user address of the user to grant ownership
    * privileges (e.g. "user@host.org").
    */
    void grantOwnership(String address);

    /**
     * Destroys the chat room.
     * @param reason the reason for destroying.
     * @param alternateAddress the alternate address
     * @return <tt>true</tt> if the room is destroyed.
     */
    boolean destroy(String reason, String alternateAddress);

    /**
     * Gets the name of this chat room as a JID.
     * @return the name of this chat room as a JID.
     */
    EntityBareJid getRoomJid();

    /**
     * Finds chat member for given MUC jid.
     *
     * @param mucJid full MUC jid of the user for whom we want to find chat
     *               member instance. Ex. chatroom1@muc.server.com/nick1234
     *
     * @return an instance of <tt>XmppChatMember</tt> for given MUC jid or
     *         <tt>null</tt> if not found.
     */
    ChatRoomMember findChatMember(Jid mucJid);

    /**
     * @return the list of all our presence {@link ExtensionElement}s.
     */
    Collection<ExtensionElement> getPresenceExtensions();

    /**
     * Checks if a packet extension is already in the presence.
     *
     * @param elementName the name of XML element of the presence extension.
     * @param namespace the namespace of XML element of the presence extension.
     *
     * @return <tt>boolean</tt>
     */
    boolean containsPresenceExtension(String elementName, String namespace);

    /**
     * Modifies our current MUC presence by adding and/or removing specified
     * extensions. The extension are compared by instance equality.
     * @param toRemove the list of extensions to be removed.
     * @param toAdd the list of extension to be added.
     */
    void modifyPresence(Collection<ExtensionElement> toRemove, Collection<ExtensionElement> toAdd);

    /**
     * TODO: only needed for startMuted. Remove once startMuted is removed.
     */
    void setConference(JitsiMeetConference conference);

    void setPresenceExtension(ExtensionElement extension, boolean remove);

    /**
     * Get the nickname of the local occupant.
     */
    String getLocalNickname();

    /**
     * Get the unique meeting ID associated by this room (set by the MUC service).
     */
    String getMeetingId();
}
