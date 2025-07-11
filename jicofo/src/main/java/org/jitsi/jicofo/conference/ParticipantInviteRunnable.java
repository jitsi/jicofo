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
package org.jitsi.jicofo.conference;

import org.jetbrains.annotations.*;
import org.jitsi.jicofo.*;
import org.jitsi.jicofo.bridge.colibri.*;
import org.jitsi.jicofo.codec.*;
import org.jitsi.jicofo.conference.source.*;
import org.jitsi.jicofo.jibri.*;
import org.jitsi.jicofo.jigasi.*;
import org.jitsi.jicofo.util.*;
import org.jitsi.jicofo.xmpp.jingle.*;
import org.jitsi.jicofo.xmpp.muc.*;
import org.jitsi.xmpp.extensions.colibri2.*;
import org.jitsi.xmpp.extensions.jingle.*;
import org.jitsi.xmpp.extensions.jingle.JingleUtils;
import org.jitsi.xmpp.extensions.jitsimeet.*;
import org.jitsi.utils.logging2.*;
import org.jivesoftware.smack.*;
import org.jivesoftware.smack.packet.*;
import org.jxmpp.jid.*;

import java.util.*;

/**
 * An {@link Runnable} which invites a participant to a conference.
 *
 * @author Pawel Domas
 * @author Boris Grozev
 */
public class ParticipantInviteRunnable implements Runnable, Cancelable
{
    private final Logger logger;

    /**
     * The {@link JitsiMeetConferenceImpl} into which a participant will be
     * invited.
     */
    private final JitsiMeetConferenceImpl meetConference;

    @NotNull private final ColibriSessionManager colibriSessionManager;

    /**
     * A flag which indicates whether channel allocation is canceled. Raising
     * this makes the allocation thread discontinue the allocation process and
     * return.
     */
    private volatile boolean canceled = false;

    /**
     * Whether to include a "start audio muted" extension when sending session-initiate.
     */
    private final boolean startAudioMuted;

    /**
     * Whether to include a "start video muted" extension when sending session-initiate.
     */
    private final boolean startVideoMuted;

    /**
     * Whether the participant should be force muted (audio).
     */
    private final boolean forceMuteAudio;

    /**
     * Whether the participant should be force muted (video).
     */
    private final boolean forceMuteVideo;

    /**
     * Indicates whether or not this task will be doing a "re-invite". It
     * means that we're going to replace a previous conference which has failed.
     * Channels are allocated on new JVB and peer is re-invited with
     * 'transport-replace' Jingle action as opposed to 'session-initiate' in
     * regular invite.
     */
    private final boolean reInvite;

    /**
     * Override super's AbstractParticipant
     */
    @NotNull private final Participant participant;

    /**
     * {@inheritDoc}
     */
    public ParticipantInviteRunnable(
            JitsiMeetConferenceImpl meetConference,
            @NotNull ColibriSessionManager colibriSessionManager,
            @NotNull Participant participant,
            boolean startAudioMuted,
            boolean startVideoMuted,
            boolean reInvite,
            Logger parentLogger)
    {
        this.meetConference = meetConference;
        this.colibriSessionManager = colibriSessionManager;

        boolean forceMuteAudio = false;
        boolean forceMuteVideo = false;
        ChatRoom chatRoom = meetConference.getChatRoom();
        if (chatRoom != null && !participant.hasModeratorRights() && !participant.shouldSuppressForceMute())
        {
            if (chatRoom.isAvModerationEnabled(MediaType.AUDIO))
            {
                forceMuteAudio = true;
            }

            if (chatRoom.isAvModerationEnabled(MediaType.VIDEO))
            {
                forceMuteVideo = true;
            }
        }

        this.forceMuteAudio = forceMuteAudio;
        this.forceMuteVideo = forceMuteVideo;

        // If the participant is force muted, communicate it from the start instead of sending MuteIqs later.
        this.startAudioMuted = startAudioMuted || forceMuteAudio;
        this.startVideoMuted = startVideoMuted || forceMuteVideo;
        this.reInvite = reInvite;
        this.participant = participant;
        logger = parentLogger.createChildLogger(getClass().getName());
        logger.addContext("participant", participant.getChatMember().getName());
    }

    /**
     * Entry point for the {@link ParticipantInviteRunnable} task.
     */
    @Override
    public void run()
    {
        try
        {
            doRun();
        }
        catch (Throwable e)
        {
            logger.error("Channel allocator failed: ", e);
            cancel();
        }
        finally
        {
            participant.inviteRunnableCompleted(this);
        }
    }

    private void doRun()
    {
        Offer offer = createOffer();
        if (canceled)
        {
            return;
        }

        ColibriAllocation colibriAllocation;
        try
        {
            Set<Media> medias = new HashSet<>();
            offer.getContents().forEach(content -> {
                // Ignore the "data" content here (SCTP).
                if (!"audio".equals(content.getName()) && !"video".equals(content.getName()))
                {
                    return;
                }
                Media media = ConferenceUtilKt.toMedia(content);
                if (media != null)
                {
                    medias.add(media);
                }
                else
                {
                    logger.warn("Failed to convert ContentPacketExtension to Media: " + content.toXML());
                }
            });
            // This makes the bridge signal its private host candidates. We enable them for backend components, because
            // they may be in the same network as the bridge, and disable them for endpoints to avoid checking
            // unnecessary pairs (unless the endpoints explicitly signal the feature).
            boolean privateAddresses =
                (participant.getChatMember().isJigasi() && JigasiConfig.config.getPrivateAddressConnectivity()) ||
                    (participant.getChatMember().isJibri() && JibriConfig.config.getPrivateAddressConnectivity());
            ParticipantAllocationParameters participantOptions = new ParticipantAllocationParameters(
                    participant.getEndpointId(),
                    participant.getStatId(),
                    participant.getChatMember().getRegion(),
                    participant.getSources(),
                    participant.useSsrcRewriting(),
                    forceMuteAudio,
                    forceMuteVideo,
                    offer.getContents().stream().anyMatch(c -> c.getName() == "data"),
                    (participant.getChatMember().getRole() == MemberRole.VISITOR),
                    privateAddresses,
                    medias);
            colibriAllocation = colibriSessionManager.allocate(participantOptions);
        }
        catch (BridgeSelectionFailedException e)
        {
            logger.error("Can not invite participant, no bridge available.");
            cancel();
            return;
        }
        catch (ConferenceAlreadyExistsException e)
        {
            logger.warn("Can not allocate colibri channels, conference already exists.");
            cancel();
            return;
        }
        catch (ColibriAllocationFailedException e)
        {
            logger.error("Failed to allocate colibri channels", e);
            cancel();
            return;
        }


        if (canceled)
        {
            return;
        }

        offer = updateOffer(offer, colibriAllocation);
        if (canceled)
        {
            return;
        }

        try
        {
            invite(offer, colibriAllocation);
        }
        catch (SmackException.NotConnectedException e)
        {
            logger.error("Failed to invite participant: ", e);
            colibriSessionManager.removeParticipant(participant.getEndpointId());
            cancel();
        }
    }

    /**
     * Raises the {@code canceled} flag, which causes the thread to not continue
     * with the allocation process.
     */
    @Override
    public void cancel()
    {
        canceled = true;
    }

    @Override
    public String toString()
    {
        return String.format(
                "%s[%s]@%d",
                this.getClass().getSimpleName(),
                participant,
                hashCode());
    }

    /**
     * {@inheritDoc}
     */
    private Offer createOffer()
    {
        OfferOptions offerOptions = new OfferOptions();
        OfferOptionsUtilKt.applyConstraints(offerOptions, participant);
        // Enable REMB only when TCC is not enabled.
        if (!offerOptions.getTcc() && participant.hasRembSupport())
        {
            offerOptions.setRemb(true);
        }

        return new Offer(new ConferenceSourceMap(), JingleOfferFactory.INSTANCE.createOffer(offerOptions));
    }

    /**
     * {@inheritDoc}
     */
    private void invite(Offer offer, ColibriAllocation colibriAllocation)
        throws SmackException.NotConnectedException
    {
        /*
           This check makes sure that when we're trying to invite
           new participant:
           - the conference has not been disposed in the meantime
           - he's still in the room
           - we have managed to send Jingle session-initiate
           We usually expire channels when participant leaves the MUC, but we
           may not have channel information set, so we have to expire it
           here.
        */
        boolean expireChannels = false;
        Jid address = participant.getMucJid();

        ChatRoom chatRoom = meetConference.getChatRoom();
        if (chatRoom == null)
        {
            // Conference disposed
            logger.info("Expiring " + address + " channels - conference disposed");

            expireChannels = true;
        }
        else if (!meetConference.hasMember(address))
        {
            // Participant has left the room
            logger.info("Expiring " + address + " channels - participant has left");

            expireChannels = true;
        }
        else if (!canceled)
        {
            if (!doInviteOrReinvite(offer, colibriAllocation))
            {
                expireChannels = true;
            }
        }

        if (expireChannels || canceled)
        {
            // Whether another thread intentionally canceled us, or there was
            // a failure to invite the participant on the jingle level, we will
            // not trigger a retry here.
            meetConference.onInviteFailed(this);
        }
    }

    /**
     * Invites or re-invites (based on the value of {@link #reInvite}) the
     * {@code participant} to the jingle session.
     * Creates and sends the appropriate Jingle IQ ({@code session-initiate} for
     * and invite or {@code transport-replace} for a re-invite) and sends it to
     * the {@code participant}. Blocks until a response is received or a timeout
     * occurs.
     *
     * @param offer The description of the offer to send (sources and a list of {@link ContentPacketExtension}s).
     * @return {@code false} on failure.
     * @throws SmackException.NotConnectedException if we are unable to send a packet because the XMPP connection is not
     * connected.
     */
    private boolean doInviteOrReinvite(Offer offer, ColibriAllocation colibriAllocation)
        throws SmackException.NotConnectedException
    {
        JingleSession jingleSession = participant.getJingleSession();

        // If we're trying to re-invite, but there's no existing jingle session, start a new one.
        boolean initiateSession = !reInvite || jingleSession == null;
        boolean ack;
        List<ExtensionElement> additionalExtensions = new ArrayList<>();

        if (startAudioMuted || startVideoMuted)
        {
            StartMutedPacketExtension startMutedExt = new StartMutedPacketExtension();
            startMutedExt.setAudioMute(startAudioMuted);
            startMutedExt.setVideoMute(startVideoMuted);
            additionalExtensions.add(startMutedExt);
        }

        // Include info about the BridgeSession which provides the transport
        additionalExtensions.add(new BridgeSessionPacketExtension(
                colibriAllocation.getBridgeSessionId(),
                colibriAllocation.getRegion()));

        // We're about to send a jingle message that will initialize or reset the sources signaled to the participant.
        // Reflect this in the participant state.
        ConferenceSourceMap sources = participant.resetSignaledSources(offer.getSources());
        if (initiateSession)
        {
            jingleSession = participant.createNewJingleSession();
            logger.info("Sending session-initiate to: " + participant.getMucJid() + " sources=" + sources);
            ack = jingleSession.initiateSession(
                    offer.getContents(),
                    additionalExtensions,
                    sources
            );
        }
        else
        {
            ack = jingleSession.replaceTransport(offer.getContents(), additionalExtensions, sources);
        }

        if (!ack)
        {
            // Failed to invite
            logger.info(
                "Expiring " + participant.getMucJid() + " channels - no RESULT for "
                    + (initiateSession ? "session-initiate"
                    : "transport-replace"));
            return false;
        }

        return true;
    }

    private @NotNull Offer updateOffer(Offer offer, ColibriAllocation colibriAllocation)
    {
        ConferenceSourceMap conferenceSources;

        if (!participant.useSsrcRewriting())
        {
            // Take all sources from participants in the conference.
            conferenceSources = meetConference.getSources().copy();
        }
        else
        {
            // Bridge will signal sources in this case.
            conferenceSources = new ConferenceSourceMap();
        }

        // Add the bridge's feedback sources.
        conferenceSources.add(colibriAllocation.getSources());
        // Remove the participant's own sources (if they're present)
        conferenceSources.remove(participant.getEndpointId());

        for (ContentPacketExtension cpe : offer.getContents())
        {
            try
            {
                // Remove empty transport PE
                IceUdpTransportPacketExtension empty = cpe.getFirstChildOfType(IceUdpTransportPacketExtension.class);
                cpe.getChildExtensions().remove(empty);

                IceUdpTransportPacketExtension copy =
                        IceUdpTransportPacketExtension.cloneTransportAndCandidates(
                                colibriAllocation.getTransport(),
                                true);

                if ("data".equalsIgnoreCase(cpe.getName()))
                {
                    SctpMapExtension sctpMap = new SctpMapExtension();
                    Integer sctpPort = colibriAllocation.getSctpPort();
                    // The SCTP port is either hard-coded non-null (colibri1) or verified while parsing the response
                    // (colibri2).
                    if (sctpPort == null)
                    {
                        throw new IllegalStateException("SCTP port must not be null");
                    }
                    sctpMap.setPort(sctpPort);
                    sctpMap.setProtocol(SctpMapExtension.Protocol.WEBRTC_CHANNEL);
                    sctpMap.setStreams(1024);

                    copy.addChildExtension(sctpMap);
                }

                cpe.addChildExtension(copy);
            }
            catch (Exception e)
            {
                logger.error(e, e);
            }

            RtpDescriptionPacketExtension rtpDescPe = JingleUtils.getRtpDescription(cpe);
            if (rtpDescPe != null)
            {
                // rtcp-mux is always used
                rtpDescPe.addChildExtension(new JingleRtcpmuxPacketExtension());
            }
        }


        return new Offer(conferenceSources, offer.getContents());
    }

    /**
     * @return the {@link Participant} associated with this
     * {@link ParticipantInviteRunnable}.
     */
    public @NotNull Participant getParticipant()
    {
        return participant;
    }
}
