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

import org.jitsi.xmpp.extensions.colibri.*;
import org.jitsi.xmpp.extensions.jingle.*;
import org.jitsi.xmpp.extensions.jitsimeet.*;
import net.java.sip.communicator.service.protocol.*;

import org.jitsi.protocol.xmpp.*;
import org.jitsi.protocol.xmpp.util.*;
import org.jitsi.utils.*;
import org.jitsi.utils.logging.*;
import org.jxmpp.jid.*;

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
     * The class logger which can be used to override logging level inherited
     * from {@link JitsiMeetConference}.
     */
    static private final Logger classLogger
        = Logger.getLogger(LipSyncHack.class);

    /**
     * Parent conference for which this instance is doing stream merging.
     */
    private final JitsiMeetConference conference;

    /**
     * Underlying Jingle operation set.
     */
    private final OperationSetJingle jingleImpl;

    /**
     * The logger for this instance. Uses the logging level either of the
     * {@link #classLogger} or {@link JitsiMeetConference#getLogger()}
     * whichever is higher.
     */
    private final Logger logger;

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
        this.conference = Objects.requireNonNull(conference, "conference");
        this.jingleImpl = Objects.requireNonNull(jingleImpl, "jingleImpl");

        this.logger = Logger.getLogger(classLogger, conference.getLogger());
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
        return p.getSourcesCopy();
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
    private boolean isOkToMergeParticipantAV(Jid participantJid,
                                             Jid ownerJid)
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
            // Do not log that error for the JVB
            if (!SSRCSignaling.SSRC_OWNER_JVB.equals(ownerJid))
            {
                logger.error(
                    "Stream owner not a participant or not found for jid: "
                        + ownerJid);
            }
            return false;
        }

        // FIXME: we do not know if the JVBs to which the SSRCs belong should
        // be merged, as the 'streamsOwner' will always be null. This could be
        // detected based on JVB version if we need to merge them at any point
        // in the future.
        boolean supportsLipSync = participant.hasLipSyncSupport();

        logger.debug(String.format(
                "Lips-sync From %s to %s, lip-sync: %s",
                ownerJid, participantJid, supportsLipSync));

        return supportsLipSync;
    }

    private void doMerge(Jid            participant,
                         Jid            owner,
                         MediaSourceMap ssrcs)
    {
        boolean merged = false;
        if (isOkToMergeParticipantAV(participant, owner))
        {
            merged = SSRCSignaling.mergeVideoIntoAudio(ssrcs);
        }

        String logMsg
            = (merged ? "Merging" : "Not merging")
                    + " A/V streams from " + owner +" to " + participant;

        // The stream is merged most of the time and it's not that interesting.
        // FIXME JVBs SSRCs are not merged currently, but maybe should be ?
        if (merged || SSRCSignaling.SSRC_OWNER_JVB.equals(owner))
        {
            logger.debug(logMsg);
        }
        else
        {
            logger.info(logMsg);
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
     * @param mucJid the MUC JID of the participant to whom Jingle notification
     *        will be sent.
     */
    private void processAllParticipantsSSRCs(
            List<ContentPacketExtension>    contents,
            Jid                             mucJid)
    {
        // Split into maps on per owner basis
        Map<Jid, MediaSourceMap> perOwnerMapping
            = SSRCSignaling.ownerMapping(contents);

        for (Map.Entry<Jid, MediaSourceMap> ownerSSRCs
                : perOwnerMapping.entrySet())
        {
            Jid ownerJid = ownerSSRCs.getKey();
            if (ownerJid != null)
            {
                MediaSourceMap ssrcMap = ownerSSRCs.getValue();
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
        JingleIQ jingleIQ,
        JingleRequestHandler requestHandler)
        throws OperationFailedException
    {
        processAllParticipantsSSRCs(jingleIQ.getContentList(), jingleIQ.getTo());

        return jingleImpl.initiateSession(jingleIQ, requestHandler);
    }

    /**
     * We're doing the same thing as in {@link #initiateSession}. So go there
     * for more docs.
     *
     * {@inheritDoc}
     */
    @Override
    public boolean replaceTransport(JingleIQ jingleIQ, JingleSession session)
        throws OperationFailedException
    {
        processAllParticipantsSSRCs(
            jingleIQ.getContentList(),
            session.getAddress());

        return jingleImpl.replaceTransport(jingleIQ, session);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public JingleIQ createTransportReplace(
        JingleSession session,
        List<ContentPacketExtension> contents)
    {
        return jingleImpl.createTransportReplace(session, contents);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public JingleIQ createSessionInitiate(
        Jid address,
        List<ContentPacketExtension> contents)
    {
        return jingleImpl.createSessionInitiate(address, contents);
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
            MediaSourceMap ssrcMap,
            MediaSourceGroupMap ssrcGroupMap,
            JingleSession       session)
    {
        Jid mucJid = session.getAddress();
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
                doMerge(mucJid, owner, ssrcMap);
                ssrcMap.remove("audio", audioSsrc);
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
            MediaSourceMap ssrcMap,
            MediaSourceGroupMap ssrcGroupMap,
            JingleSession       session)
    {
        jingleImpl.sendRemoveSourceIQ(ssrcMap, ssrcGroupMap, session);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void terminateSession(JingleSession    session,
                                 Reason           reason,
                                 String           msg,
                                 boolean          sendTerminate)
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
