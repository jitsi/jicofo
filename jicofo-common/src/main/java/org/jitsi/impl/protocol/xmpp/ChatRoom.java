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

import org.jetbrains.annotations.*;
import org.jitsi.jicofo.xmpp.muc.*;
import org.jitsi.utils.*;
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
     * Joins this chat room with the nickname of the local user so that the
     * user would start receiving events and messages for it.
     */
    void join()
        throws SmackException, XMPPException, InterruptedException;

    @NotNull XmppProvider getXmppProvider();

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
     * Returns a <tt>List</tt> of <tt>ChatRoomMember</tt>s corresponding to all
     * members currently participating in this room.
     *
     * @return a <tt>List</tt> of <tt>ChatRoomMember</tt> instances
     * corresponding to all room members.
     */
    List<ChatRoomMember> getMembers();

    /**
     * Returns the number of members that are currently in this chat room. Note that this does not include the MUC
     * occupant of the local participant.
     */
    int getMembersCount();

    /**
     * Returns the number of members that currently have their audio sources unmuted.
     */
    int getAudioSendersCount();

    /**
     * Returns the number of members that currently have their video sources unmuted.
     */
    int getVideoSendersCount();

    /**
    * Grants ownership privileges to another user. Room owners may grant
    * ownership privileges. Some room implementations will not allow to grant
    * ownership privileges to other users. An owner is allowed to change
    * defining room features as well as perform all administrative functions.
    *
    * @param member the member to grant ownership to.
    */
    void grantOwnership(@NotNull ChatRoomMember member);

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
    ChatRoomMember getChatMember(EntityFullJid mucJid);

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
     * Add a [ChatRoomListener] to the list of listeners to be notified of events from this [ChatRoom].
     */
    void addListener(@NotNull ChatRoomListener listener);

    /**
     * Removes a [ChatRoomListener] from the list of listeners to be notified of events from this [ChatRoom].
     */
    void removeListener(@NotNull ChatRoomListener listener);

    void setPresenceExtension(ExtensionElement extension, boolean remove);

    /**
     * Get the unique meeting ID associated by this room (set by the MUC service).
     */
    String getMeetingId();

    /**
     * Whether A/V moderation is enabled.
     * @param mediaType the media type.
     * @return whether A/V moderation is enabled.
     */
    boolean isAvModerationEnabled(MediaType mediaType);

    /**
     * Sets new value for A/V moderation.
     * @param mediaType the media type.
     * @param value the new value.
     */
    void setAvModerationEnabled(MediaType mediaType, boolean value);

    /**
     * Updates the list of members that are allowed to unmute audio or video.
     * @param whitelists a map with string keys (MediaType.AUDIO or MediaType.VIDEO).
     */
    void updateAvModerationWhitelists(Map<String, List<String>> whitelists);

    /**
     * Checks the whitelists whether the supplied jid is allowed from a moderator to unmute.
     * @param jid the jid to check.
     * @param mediaType type of media for which we are checking.
     * @return <tt>true</tt> if the member is allowed to unmute, false otherwise.
     */
    boolean isMemberAllowedToUnmute(Jid jid, MediaType mediaType);

    /**
     * Checks whether this is a breakout room or not.
     * @return <tt>true</tt> if it is, <tt>false</tt> otherwise.
     */
    boolean isBreakoutRoom();

    /**
     * Gets the main room name (JID) when in a breakout room.
     * @return The main room JID as a string.
     */
    String getMainRoom();

    @NotNull
    OrderedJsonObject getDebugState();
}
