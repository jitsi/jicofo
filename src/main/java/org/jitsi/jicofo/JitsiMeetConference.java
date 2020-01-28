/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2016 Atlassian Pty Ltd
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
package org.jitsi.jicofo;

import net.java.sip.communicator.service.protocol.*;
import org.jitsi.jicofo.bridge.*;
import org.jitsi.protocol.xmpp.*;
import org.jitsi.utils.logging.*;
import org.jxmpp.jid.*;

import java.util.*;

/**
 * The conference interface extracted from {@link JitsiMeetConferenceImpl} for
 * the unit tests purpose. There are many method that would be a good candidate
 * for ending up in this interface, but until the only reason for this interface
 * are tests, only methods actually used there are listed here.
 *
 * @author Pawel Domas
 */
public interface JitsiMeetConference
{
    /**
     * @return the <tt>Logger</tt> instance which might be used to inherit
     * the logging level assigned on the per conference basis.
     */
    Logger getLogger();

    /**
     * Checks how many {@link Participant}s are in the conference.
     * @return an integer greater than 0.
     */
    int getParticipantCount();

    /**
     * Find {@link Participant} for given MUC JID.
     *
     * @param mucJid participant's MUC jid (ex. "room@muc.server.com/nickname").
     *
     * @return {@link Participant} instance or <tt>null</tt> if not found.
     */
    Participant findParticipantForRoomJid(Jid mucJid);

    /**
     * @return the list of {@link Bridge} currently used by this
     * conference.
     */
    List<Bridge> getBridges();

    /**
     * Returns the name of conference multi-user chat room.
     */
    public EntityBareJid getRoomName();

    /**
     * Returns focus MUC JID if it is in the room or <tt>null</tt> otherwise.
     * JID example: room_name@muc.server.com/focus_nickname.
     */
    public EntityFullJid getFocusJid();

    /**
     * Returns <tt>ChatRoom2</tt> instance for the MUC this instance is
     * currently in or <tt>null</tt> if it isn't in any.
     */
    public ChatRoom2 getChatRoom();

    /**
     * Sets the value of the <tt>startMuted</tt> property of this instance.
     *
     * @param startMuted the new value to set on this instance. The specified
     * array is copied.
     */
    void setStartMuted(boolean[] startMuted);

    /**
     * Gets the role of a member in the conference.
     * @param jid the member whose role is to be determined.
     * @return The member's role or <tt>null</tt> if the JID is not a member.
     */
    ChatRoomMemberRole getRoleForMucJid(Jid jid);

    /**
     * Checks if given MUC jid belongs to the focus user.
     *
     * @param jid the full MUC address to check.
     *
     * @return <tt>true</tt> if given <tt>jid</tt> belongs to the focus
     *         participant or <tt>false</tt> otherwise.
     */
    boolean isFocusMember(Jid jid);

    /**
     * Whether this conference should be considered when generating statistics.
     */
    boolean includeInStatistics();
}
