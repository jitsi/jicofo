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

import org.jitsi.jicofo.conference.*;
import org.jitsi.jicofo.conference.source.*;
import org.jitsi.utils.*;
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
     * Checks if a source has valid stream ID('msid'). The 'default' stream id is not considered a valid one.
     */
    private static boolean hasValidStreamId(Source source)
    {
        String streamId = getStreamId(source);
        return streamId != null
                && !"default".equalsIgnoreCase(streamId.trim())
                && !"-".equals(streamId.trim());
    }

    /**
     * Get's WebRTC stream ID extracted from "msid" SSRC parameter.
     * @param source {@link Source} that describes the SSRC for which we want to obtain WebRTC stream ID.
     * @return WebRTC stream ID that is the first part of "msid" SSRC parameter.
     */
    private static String getStreamId(Source source)
    {
        String msid = source.getMsid();

        if (msid == null)
        {
            return null;
        }

        String[] streamAndTrack = msid.split(" ");
        String streamId = streamAndTrack.length == 2 ? streamAndTrack[0] : null;
        if (streamId != null)
        {
            streamId = streamId.trim();
            if (streamId.isEmpty())
            {
                streamId = null;
            }
        }
        return streamId;
    }
    /**
     * Get's WebRTC track ID extracted from "msid" SSRC parameter.
     * @param source {@link Source} that describes the SSRC for which we want to obtain WebRTC stream ID.
     * @return WebRTC track ID that is the second part of "msid" SSRC parameter.
     */
    private static String getTrackId(Source source)
    {
        String msid = source.getMsid();

        if (msid == null)
            return null;

        String[] streamAndTrack = msid.split(" ");
        String trackId = streamAndTrack.length == 2 ? streamAndTrack[1] : null;
        if (trackId != null)
        {
            trackId = trackId.trim();
            if (trackId.isEmpty())
            {
                trackId = null;
            }
        }
        return trackId;
    }

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

    private boolean receiverSupportsLipSync(Jid receiverJid)
    {
        Participant receiver = conference.findParticipantForRoomJid(receiverJid);
        return receiver != null && receiver.hasLipSyncSupport();
    }

    /**
     * Creates a new {@link EndpointSourceSet} by "merging" all video sources with an MSID in the given set into the
     * first audio source with a valid stream ID (see {@link #hasValidStreamId(Source)})
     *
     * @param sourceSet the original set of sources, which are to be "merged".
     *
     * @return the modified set of sources, with video "merged" into audio.
     */
    private EndpointSourceSet mergeVideoIntoAudio(EndpointSourceSet sourceSet)
    {
        // We want to sync video stream with the first valid audio stream
        Source audioSource
                = sourceSet.getSources().stream()
                .filter(s -> s.getMediaType() == MediaType.AUDIO)
                .filter(LipSyncHack::hasValidStreamId)
                .findFirst().orElse(null);
        // Nothing to sync to
        if (audioSource == null)
        {
            return sourceSet;
        }

        String audioStreamId = getStreamId(audioSource);

        Set<Source> mergedSources = new HashSet<>();
        // There are multiple video SSRCs in simulcast
        // FIXME this will not work with more than 1 video stream
        //       per participant, as it will merge them into single stream
        for (Source source : sourceSet.getSources())
        {
            if (source.getMediaType() != MediaType.VIDEO)
            {
                mergedSources.add(source);
            }
            else if (!hasValidStreamId(source))
            {
                mergedSources.add(source);
            }
            else
            {
                // "merge" the video source into the audio media stream by replacing the stream ID
                String trackId = getTrackId(source);
                mergedSources.add(new Source(
                        source.getSsrc(),
                        source.getMediaType(),
                        source.getName(),
                        audioStreamId + " " + trackId,
                        false));
                logger.debug("Merged video SSRC " + source.getSsrc() + " into " + audioSource);
            }
        }

        return new EndpointSourceSet(mergedSources, sourceSet.getSsrcGroups());
    }

    /**
     * Creates a new {@link ConferenceSourceMap} by "merging" each entry of the given map.
     * See {@link #mergeVideoIntoAudio(EndpointSourceSet)}
     * @param sources the original source map, which is to be "merged".
     * @return the modified source map.
     */
    private ConferenceSourceMap mergeVideoIntoAudio(ConferenceSourceMap sources)
    {
        ConferenceSourceMap mergedSources = new ConferenceSourceMap();
        for (Map.Entry<Jid, EndpointSourceSet> entry : sources.entrySet())
        {
            Jid ownerJid = entry.getKey();
            mergedSources.add(ownerJid, mergeVideoIntoAudio(entry.getValue()));
        }

        return mergedSources;
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
            JingleRequestHandler requestHandler,
            ConferenceSourceMap sources,
            boolean encodeSourcesAsJson)
        throws SmackException.NotConnectedException
    {
        ConferenceSourceMap mergedSources = sources;
        if (receiverSupportsLipSync(to))
        {
            logger.debug("Using lip-sync for " + to);
            mergedSources = mergeVideoIntoAudio(sources);
        }

        return jingleImpl.initiateSession(
                to, contents, additionalExtensions, requestHandler, mergedSources, encodeSourcesAsJson);
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
            List<ExtensionElement> additionalExtensions,
            ConferenceSourceMap sources,
            boolean encodeSourcesAsJson)
        throws SmackException.NotConnectedException
    {
        ConferenceSourceMap mergedSources = sources;
        if (receiverSupportsLipSync(session.getAddress()))
        {
            logger.debug("Using lip-sync for " + session.getAddress());
            mergedSources = mergeVideoIntoAudio(sources);
        }

        return jingleImpl.replaceTransport(session, contents, additionalExtensions, mergedSources, encodeSourcesAsJson);
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
    public void sendAddSourceIQ(ConferenceSourceMap sources, JingleSession session, boolean encodeSourcesAsJson)
    {
        ConferenceSourceMap mergedSources = new ConferenceSourceMap();
        for (Map.Entry<Jid, EndpointSourceSet> entry : sources.entrySet())
        {
            Jid ownerJid = entry.getKey();
            EndpointSourceSet ownerSources = entry.getValue();

            // If this is a source-add for video only, search for an audio source in the sources previously signaled
            // by the endpoint.
            boolean haveValidAudioSource = ownerSources.getSources().stream()
                    .anyMatch(s -> s.getMediaType() == MediaType.AUDIO && hasValidStreamId(s));
            Source existingAudioSource = null;
            if (!haveValidAudioSource)
            {
                Participant owner = conference.findParticipantForRoomJid(ownerJid);
                if (owner != null)
                {
                    EndpointSourceSet existingOwnerSources = owner.getSources().get(ownerJid);
                    if (existingOwnerSources != null)
                    {
                        existingAudioSource = existingOwnerSources.getSources().stream()
                                .filter(s -> s.getMediaType() == MediaType.AUDIO && hasValidStreamId(s))
                                .findFirst().orElse(null);
                    }
                }
            }

            if (!haveValidAudioSource && existingAudioSource == null)
            {
                logger.debug("Cannot merge video into audio, no audio source exists for owner " + ownerJid + ".");
                mergedSources.add(ownerJid, ownerSources);
            }
            else if (haveValidAudioSource)
            {
                mergedSources.add(ownerJid, mergeVideoIntoAudio(ownerSources));
            }
            else
            {
                // We're using existingAudioSource only for the merge process.
                // We don't want it included in the source-add.
                Set<Source> ownerSourcesPlusExistingAudio = new HashSet<>(ownerSources.getSources());
                ownerSourcesPlusExistingAudio.add(existingAudioSource);

                EndpointSourceSet mergedOwnerSourcesPlusExistingAudio = mergeVideoIntoAudio(
                        new EndpointSourceSet(ownerSourcesPlusExistingAudio, ownerSources.getSsrcGroups()));

                Set<Source> mergedOwnerSourcesWithoutExistingAudio
                        = new HashSet<>(mergedOwnerSourcesPlusExistingAudio.getSources());
                mergedOwnerSourcesWithoutExistingAudio.remove(existingAudioSource);

                mergedSources.add(
                        ownerJid,
                        new EndpointSourceSet(mergedOwnerSourcesWithoutExistingAudio, ownerSources.getSsrcGroups()));
            }
        }

        jingleImpl.sendAddSourceIQ(mergedSources, session, encodeSourcesAsJson);
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
            ConferenceSourceMap sourcesToRemove,
            JingleSession session,
            boolean encodeSourcesAsJson)
    {
        jingleImpl.sendRemoveSourceIQ(sourcesToRemove, session, encodeSourcesAsJson);
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
