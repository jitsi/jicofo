/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2016-Present 8x8, Inc.
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

import org.jitsi.impl.protocol.xmpp.*;
import org.jitsi.jicofo.bridge.*;
import org.jitsi.jicofo.jibri.*;
import org.jitsi.jicofo.xmpp.muc.*;
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
     * @return a map of the {@link Bridge}s currently used by this
     * conference to the number of conference participants on each.
     */
    Map<Bridge, Integer> getBridges();

    /**
     * Returns the name of conference multi-user chat room.
     */
    EntityBareJid getRoomName();

    /**
     * Returns <tt>ChatRoom2</tt> instance for the MUC this instance is
     * currently in or <tt>null</tt> if it isn't in any.
     */
    ChatRoom getChatRoom();

    /**
     * Sets the value of the <tt>startMuted</tt> property of this instance.
     *
     * @param startMuted the new value to set on this instance. The specified
     * array is copied.
     */
    void setStartMuted(boolean[] startMuted);

    default JibriRecorder getJibriRecorder()
    {
        return null;
    }

    default JibriSipGateway getJibriSipGateway()
    {
        return null;
    }


    /**
     * Gets the role of a member in the conference.
     * @param jid the member whose role is to be determined.
     * @return The member's role or <tt>null</tt> if the JID is not a member.
     */
    MemberRole getRoleForMucJid(Jid jid);

    /**
     * Whether this conference should be considered when generating statistics.
     */
    boolean includeInStatistics();
}
