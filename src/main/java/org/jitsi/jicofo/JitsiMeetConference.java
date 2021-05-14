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

import org.jetbrains.annotations.*;
import org.jitsi.impl.protocol.xmpp.*;
import org.jitsi.jicofo.bridge.*;
import org.jitsi.jicofo.jibri.*;
import org.jitsi.jicofo.xmpp.*;
import org.jitsi.jicofo.xmpp.muc.*;
import org.jitsi.utils.*;
import org.jitsi.xmpp.extensions.jibri.*;
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

    /**
     * Process a Jibri-related IQ. This could be a request coming from the client, or from a jibri instance.
     * If the request is not related to this conference, this should return {@link IqProcessingResult.NotProcessed}.
     */
    @NotNull IqProcessingResult handleJibriRequest(@NotNull IqRequest<JibriIq> request);

    /**
     * Used for av moderation, when we want to mute all participants that are not moderators.
     * @param mediaType the media type we want to mute.
     */
    void muteAllNonModeratorParticipants(MediaType mediaType);

    /**
     * Return {@code true} if the user with the given JID should be allowed to invite jigasi to this conference.
     */
    boolean acceptJigasiRequest(@NotNull Jid from);

    /**
     * Handle a request to mute or unmute a participant. May block for a response from jitsi-videobridge.
     * @param muterJid MUC jid of the participant that requested mute status change, or {@code null}. When {@code null},
     * no permission checks will be performed.
     * @param toBeMutedJid MUC jid of the participant whose mute status will be changed.
     * @param doMute {@code true} to mute, {@code false} to unmute.
     * @param mediaType the {@link MediaType} of the channel to mute, either AUDIO or VIDEO.
     * @return {@link JitsiMeetConferenceImpl.MuteResult#NOT_ALLOWED} if {@code muterJid} is not allowed to mute/unmute,
     * {@link JitsiMeetConferenceImpl.MuteResult#ERROR} if the operation was not successful, and
     * {@link JitsiMeetConferenceImpl.MuteResult#SUCCESS} if it was successful.
     */
    @NotNull
    JitsiMeetConferenceImpl.MuteResult handleMuteRequest(
            Jid muterJid,
            Jid toBeMutedJid,
            boolean doMute,
            MediaType mediaType);

    /**
     * Handles used chat room being destroyed.
     * @param reason the reason for it.
     */
    void handleRoomDestroyed(String reason);
}
