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
package org.jitsi.jicofo.conference;

import org.jetbrains.annotations.*;
import org.jitsi.jicofo.MediaType;
import org.jitsi.jicofo.bridge.*;
import org.jitsi.jicofo.jibri.*;
import org.jitsi.jicofo.xmpp.*;
import org.jitsi.jicofo.xmpp.muc.*;
import org.jitsi.utils.*;
import org.jitsi.xmpp.extensions.jibri.*;
import org.jivesoftware.smack.packet.*;
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
public interface JitsiMeetConference extends XmppProvider.Listener
{
    /**
     * Checks how many {@link Participant}s are in the conference. This includes visitors.
     * @return an integer equal to or greater than 0.
     */
    int getParticipantCount();

    /**
     * @return the JID of the main room if this is is breakout room, and null otherwise.
     */
    EntityBareJid getMainRoomJid();

    /**
     * @return the JIDs of the connected visitor rooms.
     */
    List<EntityBareJid> getVisitorRoomsJids();

    /** Return the number of visitors in the conference */
    long getVisitorCount();

    /** Whether stats for this conference should be exported to rtcstats. */
    boolean isRtcStatsEnabled();

    /** Get the meeting ID associated with the conference */
    @Nullable
    String getMeetingId();

    /**
     * Find {@link Participant} for given MUC JID.
     *
     * @param mucJid participant's MUC jid (ex. "room@muc.server.com/nickname").
     *
     * @return {@link Participant} instance or <tt>null</tt> if not found.
     */
    Participant getParticipant(Jid mucJid);

    /**
     * @return the set of regions of the bridges currently in the conference.
     */
    @NotNull Set<String> getBridgeRegions();

    /**
     * Returns the name of conference multi-user chat room.
     */
    EntityBareJid getRoomName();

    /**
     * Returns <tt>ChatRoom2</tt> instance for the MUC this instance is
     * currently in or <tt>null</tt> if it isn't in any.
     */
    @Nullable ChatRoom getChatRoom();

    @Nullable
    default JibriRecorder getJibriRecorder()
    {
        return null;
    }

    @Nullable
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
     * Used for av moderation, when we want to mute all participants.
     * @param mediaType the media type we want to mute.
     * @param actor the entity that requested the mute.
     */
    void muteAllParticipants(MediaType mediaType, EntityFullJid actor);

    /**
     * Return {@code true} if the user with the given JID should be allowed to invite jigasi to this conference.
     */
    boolean acceptJigasiRequest(@NotNull Jid from);

    /**
     * Handle a request to mute or unmute a participant. May block for a response from jitsi-videobridge.
     * @param muterJid MUC jid of the participant that requested mute status change.
     * @param toBeMutedJid MUC jid of the participant whose mute status will be changed.
     * @param doMute {@code true} to mute, {@code false} to unmute.
     * @param mediaType the {@link MediaType} of the channel to mute, either AUDIO or VIDEO.
     * @return {@link MuteResult#NOT_ALLOWED} if {@code muterJid} is not allowed to mute/unmute,
     * {@link MuteResult#ERROR} if the operation was not successful, and
     * {@link MuteResult#SUCCESS} if it was successful.
     */
    @NotNull
    MuteResult handleMuteRequest(
            @NotNull Jid muterJid,
            @NotNull Jid toBeMutedJid,
            boolean doMute,
            @NotNull MediaType mediaType);

    @NotNull
    OrderedJsonObject getDebugState();

    /** Get the stats for this conference that should be exported to rtcstats. */
    @NotNull
    OrderedJsonObject getRtcstatsState();

    /** Move (reinvite) an endpoint in this conference. Return true if the endpoint was moved. */
    boolean moveEndpoint(@NotNull String endpointId, Bridge bridge);

    /**
     * Move (reinvite) a specific number of endpoints from the conference from a specific bridge. The implementation
     * decides which endpoints to move.
     *
     * @param bridge the bridge from which to move endpoints.
     * @param numEps the number of endpoints to move.
     * @return the number of endpoints moved.
     */
    int moveEndpoints(@NotNull Bridge bridge, int numEps);

    /** Get information about the bridges currently used by this conference. */
    Map<Bridge, ConferenceBridgeProperties> getBridges();

    boolean isStarted();

    @Nullable
    String redirectVisitor(boolean visitorRequested, @Nullable String userId, @Nullable String groupId)
            throws Exception;

    void setPresenceExtension(@NotNull ExtensionElement extension);
}
