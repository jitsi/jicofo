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
package org.jitsi.jicofo;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jitsimeet
          .SSRCInfoPacketExtension;
import net.java.sip.communicator.util.Logger;

import org.jitsi.protocol.xmpp.*;
import org.jitsi.protocol.xmpp.util.*;
import org.jitsi.util.*;

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
     * The logger used in <tt>LipSyncHack</tt>.
     */
    static private final Logger logger = Logger.getLogger(LipSyncHack.class);

    /**
     * Parent conference for which this instance is doing stream merging.
     */
    private final JitsiMeetConference conference;

    /**
     * Underlying Jingle operation set.
     */
    private final OperationSetJingle jingleImpl;

    /**
     * Creates new instance of <tt>LipSyncHack</tt> for given conference.
     *
     * @param conference parent <tt>JitsiMeetConference</tt> for which this
     *        instance will be doing lip-sync hack.
     * @param jingleImpl the Jingle operations set that will be wrapped in order
     *        to modify some of the Jingle requests.
     */
    public LipSyncHack(JitsiMeetConference    conference,
                       OperationSetJingle     jingleImpl)
    {
        this.conference = conference;
        this.jingleImpl = jingleImpl;
    }

    private MediaSSRCMap getParticipantSSRCMap(String mucJid)
    {
        Participant p = conference.findParticipantForRoomJid(mucJid);
        if (p == null)
        {
            logger.warn("No participant found for: " + mucJid);
            // Return empty to avoid null checks
            return new MediaSSRCMap();
        }
        return p.getSSRCsCopy();
    }

    /**
     * Decides whether or not it's ok to merge streams sent from one participant
     * to another.
     *
     * @param participantJid the MUC JID of the participant to whom we're going
     *        to send stream info.
     * @param ownerJid the MUC JID of the owner of the streams which are to be
     *        advertised to given <tt>participantJid</tt>
     *
     * @return <tt>true</tt> if it's OK to merge audio+video streams or
     *         <tt>false</tt> otherwise.
     */
    private boolean isOkToMergeParticipantAV(String    participantJid,
                                             String    ownerJid)
    {
        Participant participant
            = conference.findParticipantForRoomJid(participantJid);
        if (participant == null)
        {
            logger.error("No target participant found for: " + participantJid);
            return false;
        }

        Participant streamsOwner
            = conference.findParticipantForRoomJid(ownerJid);
        if (streamsOwner == null)
        {
            logger.error(
                    "Stream owner not a participant or not found for jid: "
                        + ownerJid);
            return false;
        }

        Boolean isVideoMuted = streamsOwner.isVideoMuted();
        if (isVideoMuted == null)
        {
            logger.warn("No 'videomuted' presence extension for " + ownerJid);
        }

        boolean supportsLipSync = participant.hasLipSyncSupport();

        // FIXME switch to debug level after some more testing
        logger.info(String.format(
                "Lips-sync From %s to %s, lip-sync: %s, video muted: %s",
                participantJid, ownerJid, supportsLipSync, isVideoMuted));

        return supportsLipSync && Boolean.FALSE.equals(isVideoMuted);
    }

    private void doMerge(String          participant,
                         String          owner,
                         MediaSSRCMap    ssrcs)
    {
        boolean merged = false;
        if (isOkToMergeParticipantAV(participant, owner))
        {
            merged = SSRCSignaling.mergeVideoIntoAudio(ssrcs);
        }
        // FIXME switch to debug level after some more testing
        logger.info(
               (merged ? "Merging" : "Not merging")
                    + " A/V streams from " + owner +" to " + participant);
    }

    /**
     * Does the lip-sync processing of given Jingle content list that contains
     * streams description for the whole conference which are about to be sent
     * to one of the conference participants. It will do audio and video stream
     * merging for the purpose of enabling lip-sync functionality on the client.
     *
     * @param contents a list of Jingle contents which describes audio and video
     *        streams for the whole conference.
     * @param mucJid the MUC JID of the participant to whom Jingle notification
     *        will be sent.
     */
    private void processAllParticipantsSSRCs(
            List<ContentPacketExtension>    contents,
            String                          mucJid)
    {
        // Split into maps on per owner basis
        Map<String, MediaSSRCMap> perOwnerMapping
            = SSRCSignaling.ownerMapping(contents);

        for (Map.Entry<String, MediaSSRCMap> ownerSSRCs
                : perOwnerMapping.entrySet())
        {
            String ownerJid = ownerSSRCs.getKey();
            if (!StringUtils.isNullOrEmpty(ownerJid))
            {
                MediaSSRCMap ssrcMap = ownerSSRCs.getValue();
                if (ssrcMap != null)
                {
                    doMerge(mucJid, ownerJid, ssrcMap);
                }
                else
                    logger.error("'ssrcMap' is null");
            }
            else
                logger.warn("'owner' is null");
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
            boolean                         useBundle,
            String                          address,
            List<ContentPacketExtension>    contents,
            JingleRequestHandler            requestHandler,
            boolean[]                       startMuted)
    {
        processAllParticipantsSSRCs(contents, address);

        return jingleImpl.initiateSession(
                useBundle, address, contents, requestHandler, startMuted);
    }

    /**
     * We're doing the same thing as in {@link #initiateSession}. So go there
     * for more docs.
     *
     * {@inheritDoc}
     */
    @Override
    public boolean replaceTransport(
            boolean                         useBundle,
            JingleSession                   session,
            List<ContentPacketExtension>    contents,
            boolean[]                       startMuted)
    {
        processAllParticipantsSSRCs(contents, session.getAddress());

        return jingleImpl.replaceTransport(
                useBundle, session, contents, startMuted);
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
    public void sendAddSourceIQ(
            MediaSSRCMap        ssrcMap,
            MediaSSRCGroupMap   ssrcGroupMap,
            JingleSession       session)
    {
        String mucJid = session.getAddress();
        // If this is source add for video only then add audio for merge process
        for (SourcePacketExtension videoSSRC
                : ssrcMap.getSSRCsForMedia("video"))
        {
            String owner = SSRCSignaling.getSSRCOwner(videoSSRC);
            SourcePacketExtension audioSSRC
                = ssrcMap.findSSRCforOwner("audio", owner);
            if (audioSSRC == null)
            {
                // Try finding corresponding audio from the global conference
                // state for this owner
                MediaSSRCMap allOwnersSSRCs = getParticipantSSRCMap(owner);
                List<SourcePacketExtension> audioSSRCs
                    = allOwnersSSRCs.getSSRCsForMedia("audio");
                audioSSRC = SSRCSignaling.getFirstWithMSID(audioSSRCs);
            }
            if (audioSSRC != null)
            {
                ssrcMap.addSSRC("audio", audioSSRC);
                doMerge(mucJid, owner, ssrcMap);
                ssrcMap.remove("audio", audioSSRC);
            }
            else
            {
                logger.warn("No corresponding audio found for: " + owner
                            + " 'source-add' to: " + mucJid);
            }
        }
        jingleImpl.sendAddSourceIQ(ssrcMap, ssrcGroupMap, session);
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
    public void sendRemoveSourceIQ(
            MediaSSRCMap        ssrcMap,
            MediaSSRCGroupMap   ssrcGroupMap,
            JingleSession       session)
    {
        jingleImpl.sendRemoveSourceIQ(ssrcMap, ssrcGroupMap, session);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void terminateSession(JingleSession session, Reason reason)
    {
        jingleImpl.terminateSession(session, reason);
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
