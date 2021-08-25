/*
 * Jicofo, the Jitsi Conference Focus.
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
package org.jitsi.jicofo.lipsynchack;

import org.jitsi.jicofo.*;
import org.jitsi.jicofo.conference.source.*;
import org.jitsi.xmpp.extensions.colibri.*;
import org.jitsi.xmpp.extensions.jingle.*;
import org.jitsi.xmpp.extensions.jitsimeet.*;

import org.jitsi.protocol.xmpp.*;
import org.jitsi.utils.logging2.*;
import org.jivesoftware.smack.*;
import org.jivesoftware.smack.packet.*;
import org.jxmpp.jid.*;

import javax.validation.constraints.*;
import java.util.*;

/**
 * Here we're doing audio + video stream merging in order to take advantage
 * of WebRTC's lip-sync feature.
 *
 * In Jitsi-meet we obtain separate audio and video streams and send their SSRC
 * description to Jicofo which then propagates that to other conference
 * participants. Because they are separate stream, the WebRTC stack wil not try
 * to synchronize audio and video streams which will often result in bad user
 * experience.
 *
 * We are not merging the streams on the client which would result in all
 * clients receiving them merged, because of few issues:
 * 1. Chrome will not play audio for such merged stream if the user has video
 *    muted: https://bugs.chromium.org/p/chromium/issues/detail?id=403710
 *    So we will only merge the stream if the owner has his video unmuted at
 *    the time when it's being advertised to other participant. This is subject
 *    to some race conditions and will not always work. That's why this hack is
 *    not enabled by default.
 * 2. We need separate streams for doing video mute and screen streaming. When
 *    video is being muted it's stream is being removed and added on unmute. And
 *    when we're doing screen streaming the video stream is replaced with the
 *    screen one. Now when the stream is merged audio and video are becoming
 *    tracks of the same stream and on the client side we need to be able to see
 *    when they change. Unfortunately "track added" and "track removed" events
 *    are only supported by Chrome. Firefox will display the video fine after
 *    track change, but there will be no event, Temasys has no events and track
 *    changes are not supported.
 *
 * The class wraps {@link OperationSetJingle} and modifies some of the request
 * in order to do stream merging when it's needed.
 *
 * @author Pawel Domas
 */
public class LipSyncHack implements OperationSetJingle
{
    /**
     * Parent conference for which this instance is doing stream merging.
     */
    private final JitsiMeetConference conference;

    /**
     * Underlying Jingle operation set.
     */
    private final OperationSetJingle jingleImpl;

    private final Logger logger;

    /**
     * Creates new instance of <tt>LipSyncHack</tt> for given conference.
     *
     * @param conference parent <tt>JitsiMeetConference</tt> for which this
     *        instance will be doing lip-sync hack.
     * @param jingleImpl the Jingle operations set that will be wrapped in order
     *        to modify some of the Jingle requests.
     */
    public LipSyncHack(
            @NotNull JitsiMeetConference conference,
            @NotNull OperationSetJingle jingleImpl,
            @NotNull Logger parentLogger)
    {
        this.conference = conference;
        this.jingleImpl = jingleImpl;
        this.logger = parentLogger.createChildLogger(getClass().getName());
    }

    private MediaSourceMap getParticipantSSRCMap(Jid mucJid)
    {
        Participant p = conference.findParticipantForRoomJid(mucJid);
        if (p == null)
        {
            logger.warn("No participant found for: " + mucJid);
            // Return empty to avoid null checks
            return new MediaSourceMap();
        }
        return LipSyncHackUtilsKt.toMediaSourceMap(p.getSources()).getSources();
    }


    private boolean receiverSupportsLipSync(Jid receiverJid)
    {
        Participant receiver = conference.findParticipantForRoomJid(receiverJid);
        return receiver != null && receiver.hasLipSyncSupport();
    }

    private void doMerge(Jid owner, MediaSourceMap ssrcs)
    {
        if (!ParticipantChannelAllocator.SSRC_OWNER_JVB.equals(owner))
        {
            boolean merged = SSRCSignaling.mergeVideoIntoAudio(ssrcs);
            logger.debug(merged ? "Merging" : "Not merging" + " A/V streams for owner " + owner);
        }
    }

    /**
     * Does the lip-sync processing of given Jingle content list that contains
     * streams description for the whole conference which are about to be sent
     * to one of the conference participants. It will do audio and video stream
     * merging for the purpose of enabling lip-sync functionality on the client.
     *
     * @param contents a list of Jingle contents which describes audio and video
     *        streams for the whole conference.
     */
    private void processAllParticipantsSSRCs(List<ContentPacketExtension> contents)
    {
        // Split into maps on per owner basis
        Map<Jid, MediaSourceMap> perOwnerMapping = SSRCSignaling.ownerMapping(contents);

        for (Map.Entry<Jid, MediaSourceMap> ownerSSRCs : perOwnerMapping.entrySet())
        {
            Jid ownerJid = ownerSSRCs.getKey();
            if (ownerJid != null)
            {
                MediaSourceMap ssrcMap = ownerSSRCs.getValue();
                if (ssrcMap != null)
                {
                    doMerge(ownerJid, ssrcMap);
                }
                else
                {
                    logger.error("'ssrcMap' is null");
                }
            }
            else
            {
                logger.warn("'owner' is null");
            }
        }
    }

    /**
     * The <tt>LipSyncHack</tt> will attempt to merge all video streams into
     * corresponding audio streams. A corresponding stream is the one that
     * belongs to the same participant which we figure out by checking 'owner'
     * included {@link SSRCInfoPacketExtension}. Once the processing is done
     * the job is passed to the underlying Jingle operation set to send
     * the notification.
     *
     * {@inheritDoc}
     */
    @Override
    public boolean initiateSession(
            Jid to,
            List<ContentPacketExtension> contents,
            List<ExtensionElement> additionalExtensions,
            JingleRequestHandler requestHandler)
        throws SmackException.NotConnectedException
    {
        if (receiverSupportsLipSync(to))
        {
            logger.debug("Using lip-sync for " + to);
            processAllParticipantsSSRCs(contents);
        }

        return jingleImpl.initiateSession(to, contents, additionalExtensions, requestHandler);
    }

    @Override
    public Jid getOurJID()
    {
        return jingleImpl.getOurJID();
    }

    /**
     * We're doing the same thing as in {@link #initiateSession}. So go there
     * for more docs.
     *
     * {@inheritDoc}
     */
    @Override
    public boolean replaceTransport(
            @NotNull JingleSession session,
            List<ContentPacketExtension> contents,
            List<ExtensionElement> additionalExtensions)
        throws SmackException.NotConnectedException
    {
        if (receiverSupportsLipSync(session.getAddress()))
        {
            logger.debug("Using lip-sync for " + session.getAddress());
            processAllParticipantsSSRCs(contents);
        }

        return jingleImpl.replaceTransport(session, contents, additionalExtensions);
    }

    /**
     * We'll attempt to merge any video into the corresponding audio stream
     * (figured out by stream 'owner'). If given notification contains only
     * video SSRC we'll look up global conference state held by
     * {@link JitsiMeetConference} in order to find the matching audio stream.
     *
     * After the processing the job is passed to the underlying Jingle operation
     * set to send the request.
     *
     * {@inheritDoc}
     */
    @Override
    public void sendAddSourceIQ(ConferenceSourceMap sources, JingleSession session)
    {
        SourceMapAndGroupMap sourceMapAndGroupMap = LipSyncHackUtilsKt.toMediaSourceMap(sources);
        MediaSourceMap ssrcMap = sourceMapAndGroupMap.getSources();

        // If this is source add for video only then add audio for merge process
        for (SourcePacketExtension videoSsrc
                : ssrcMap.getSourcesForMedia("video"))
        {
            Jid owner = SSRCSignaling.getSSRCOwner(videoSsrc);
            SourcePacketExtension audioSsrc
                = ssrcMap.findSsrcForOwner("audio", owner);
            if (audioSsrc == null)
            {
                // Try finding corresponding audio from the global conference
                // state for this owner
                MediaSourceMap allOwnersSSRCs = getParticipantSSRCMap(owner);
                List<SourcePacketExtension> audioSSRCs
                    = allOwnersSSRCs.getSourcesForMedia("audio");
                audioSsrc = SSRCSignaling.getFirstWithMSID(audioSSRCs);
            }
            if (audioSsrc != null)
            {
                ssrcMap.addSource("audio", audioSsrc);
                doMerge(owner, ssrcMap);
                ssrcMap.remove("audio", audioSsrc);
            }
            else
            {
                logger.warn("No corresponding audio found for: " + owner + " 'source-add' to: " + session.getAddress());
            }
        }
        jingleImpl.sendAddSourceIQ(
                LipSyncHackUtilsKt.fromMediaSourceMap(
                        ssrcMap,
                        sourceMapAndGroupMap.getGroups()),
                session);
    }

    /**
     * We don't have to do anything special in 'source-remove' as long as we're
     * sending "simple" notification which does not include SSRC parameters, but
     * just the SSRC value. The client should remove all lines corresponding to
     * the SSRC. Otherwise we would have to keep the track of stuff we send to
     * each participant, as it will get out of sync with global conference state
     * held in {@link JitsiMeetConference} after a stream is merged.
     *
     * {@inheritDoc}
     */
    @Override
    public void sendRemoveSourceIQ(ConferenceSourceMap sourcesToRemove, JingleSession session)
    {
        jingleImpl.sendRemoveSourceIQ(sourcesToRemove, session);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void terminateSession(JingleSession session,
                                 Reason reason,
                                 String msg,
                                 boolean sendTerminate)
    {
        jingleImpl.terminateSession(session, reason, msg, sendTerminate);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void terminateHandlersSessions(JingleRequestHandler requestHandler)
    {
        jingleImpl.terminateHandlersSessions(requestHandler);
    }
}
