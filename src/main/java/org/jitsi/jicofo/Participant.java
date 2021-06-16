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
package org.jitsi.jicofo;

import org.jetbrains.annotations.*;
import org.jitsi.impl.protocol.xmpp.*;
import org.jitsi.utils.*;
import org.jitsi.xmpp.extensions.colibri.*;
import org.jitsi.xmpp.extensions.jingle.*;

import org.jitsi.jicofo.discovery.*;
import org.jitsi.protocol.xmpp.*;
import org.jitsi.protocol.xmpp.util.*;
import org.jitsi.utils.logging2.*;
import org.jxmpp.jid.*;

import java.time.*;
import java.util.*;

import static java.time.temporal.ChronoUnit.SECONDS;

/**
 * Class represent Jitsi Meet conference participant. Stores information about
 * Colibri channels allocated, Jingle session and media sources.
 *
 * @author Pawel Domas
 * @author Boris Grozev
 */
public class Participant
    extends AbstractParticipant
{
    /**
     * Returns the endpoint ID for a participant in the videobridge (Colibri)
     * context. This method can be used before <tt>Participant</tt> instance is
     * created for the <tt>ChatRoomMember</tt>.
     *
     * @param chatRoomMember XMPP MUC chat room member which represents a
     *                       <tt>Participant</tt>.
     */
    public static String getEndpointId(ChatRoomMember chatRoomMember)
    {
        return chatRoomMember.getName(); // XMPP MUC Nickname
    }

    /**
     * The {@code BridgeSession} of which this {@code Participant} is part of.
     *
     * Whenever this value is set to a non-null value it means that Jicofo
     * has assigned a bridge to this instance.
     */
    private JitsiMeetConferenceImpl.BridgeSession bridgeSession;

    /**
     * The {@link Clock} used by this participant.
     */
    private Clock clock = Clock.systemUTC();

    /**
     * The list stored the timestamp when the last restart requests have been received for this participant and is used
     * for rate limiting. See {@link #incrementAndCheckRestartRequests()} for more details.
     */
    private final Deque<Instant> restartRequests = new LinkedList<>();

    /**
     * MUC chat member of this participant.
     */
    private final ChatRoomMember roomMember;

    /**
     * Jingle session (if any) established with this peer.
     */
    private JingleSession jingleSession;

    private final Logger logger;

    /**
     * Stores information about bundled transport
     */
    private IceUdpTransportPacketExtension bundleTransport;

    /**
     * The list of XMPP features supported by this participant.
     */
    private List<String> supportedFeatures = new ArrayList<>();

    /**
     * State whether this participant is muted by media type.
     */
    private Map<MediaType, Boolean> mutedByMediaType = new HashMap<>();

    /**
     * Creates new {@link Participant} for given chat room member.
     *
     * @param roomMember the {@link ChatRoomMember} that represent this
     *                   participant in MUC conference room.
     */
    public Participant(@NotNull ChatRoomMember roomMember, Logger parentLogger)
    {
        super(parentLogger);

        this.roomMember = roomMember;
        this.logger = parentLogger.createChildLogger(getClass().getName());
    }

    /**
     * Returns {@link JingleSession} established with this conference
     * participant or <tt>null</tt> if there is no session yet.
     */
    public JingleSession getJingleSession()
    {
        return jingleSession;
    }

    /**
     * Sets the current {@code BridgeSession}.
     *
     * @param bridgeSession the new bridge session to set.
     * @see #bridgeSession
     */
    void setBridgeSession(JitsiMeetConferenceImpl.BridgeSession bridgeSession)
    {
        if (this.bridgeSession != null)
        {
            logger.error(String.format(
                    "Overwriting bridge session in %s new: %s old: %s",
                    this,
                    bridgeSession,
                    this.bridgeSession));
        }
        this.bridgeSession = bridgeSession;
    }

    /**
     * Sets the new clock instance to be used by this participant. Meant for testing.
     * @param newClock - the new {@link Clock}
     */
    public void setClock(Clock newClock)
    {
        this.clock = newClock;
    }

    /**
     * Sets {@link JingleSession} established with this peer.
     * @param jingleSession the new Jingle session to be assigned to this peer.
     */
    public void setJingleSession(JingleSession jingleSession)
    {
        this.jingleSession = jingleSession;
    }

    /**
     * Returns {@link ChatRoomMember} that represents this participant in
     * conference multi-user chat room.
     */
    public ChatRoomMember getChatMember()
    {
        return roomMember;
    }

    /**
     * @return {@link Clock} used by this participant instance.
     */
    public Clock getClock()
    {
        return clock;
    }

    /**
     * Returns <tt>true</tt> if this participant supports RTP bundle and RTCP
     * mux.
     */
    public boolean hasBundleSupport()
    {
        return supportedFeatures.contains(DiscoveryUtil.FEATURE_RTCP_MUX)
                && supportedFeatures.contains(DiscoveryUtil.FEATURE_RTP_BUNDLE);
    }

    /**
     * Returns <tt>true</tt> if this participant supports DTLS.
     */
    public boolean hasDtlsSupport()
    {
        return supportedFeatures.contains(DiscoveryUtil.FEATURE_DTLS);
    }

    /**
     * Returns <tt>true</tt> if this participant supports 'lip-sync' or
     * <tt>false</tt> otherwise.
     */
    public boolean hasLipSyncSupport()
    {
        return supportedFeatures.contains(DiscoveryUtil.FEATURE_LIPSYNC);
    }

    /**
     * Returns {@code true} iff this participant supports REMB.
     */
    public boolean hasRembSupport()
    {
        return supportedFeatures.contains(DiscoveryUtil.FEATURE_REMB);
    }

    /**
     * Returns {@code true} iff this participant supports TCC.
     */
    public boolean hasTccSupport()
    {
        return supportedFeatures.contains(DiscoveryUtil.FEATURE_TCC);
    }

    /**
     * Returns {@code true} iff this participant supports RTX.
     */
    public boolean hasRtxSupport()
    {
        return supportedFeatures.contains(DiscoveryUtil.FEATURE_RTX);
    }

    /**
     * Returns {@code true} iff this participant supports RED for opus.
     */
    public boolean hasOpusRedSupport()
    {
        return supportedFeatures.contains(DiscoveryUtil.FEATURE_OPUS_RED);
    }

    /**
     * Rate limiting mechanism for session restart requests received from participants.
     * The rules ar as follows:
     * - must be at least 10 second gap between the requests
     * - no more than 3 requests within the last minute
     *
     * @return {@code true} if it's okay to process the request, as in it doesn't violate the current rate limiting
     * policy, or {@code false} if the request should be denied.
     */
    public boolean incrementAndCheckRestartRequests()
    {
        final Instant now = Instant.now(clock);
        Instant previousRequest = this.restartRequests.peekLast();

        if (previousRequest == null)
        {
            this.restartRequests.add(now);

            return true;
        }

        if (previousRequest.until(now, SECONDS) < 10)
        {
            return false;
        }

        // Allow only 3 requests within the last minute
        this.restartRequests.removeIf(requestTime -> requestTime.until(now, SECONDS) > 60);
        if (this.restartRequests.size() > 2)
        {
            return false;
        }

        this.restartRequests.add(now);

        return true;
    }

    /**
     * Returns <tt>true</tt> if RTP audio is supported by this peer.
     */
    public boolean hasAudioSupport()
    {
        return supportedFeatures.contains(DiscoveryUtil.FEATURE_AUDIO);
    }

    /**
     * Returns <tt>true</tt> if RTP audio can be muted for this peer.
     */
    public boolean hasAudioMuteSupport()
    {
        return supportedFeatures.contains(DiscoveryUtil.FEATURE_AUDIO_MUTE);
    }

    /**
     * Returns <tt>true</tt> if RTP video is supported by this peer.
     */
    public boolean hasVideoSupport()
    {
        return supportedFeatures.contains(DiscoveryUtil.FEATURE_VIDEO);
    }

    /**
     * Returns <tt>true</tt> if this peer supports ICE transport.
     */
    public boolean hasIceSupport()
    {
        return supportedFeatures.contains(DiscoveryUtil.FEATURE_ICE);
    }

    /**
     * Returns <tt>true</tt> if this peer supports DTLS/SCTP.
     */
    public boolean hasSctpSupport()
    {
        return supportedFeatures.contains(DiscoveryUtil.FEATURE_SCTP);
    }

    /**
     * Sets the list of features supported by this participant.
     * @see DiscoveryUtil for the list of predefined feature constants.
     * @param supportedFeatures the list of features to set.
     */
    public void setSupportedFeatures(@NotNull List<String> supportedFeatures)
        throws UnsupportedFeatureConfigurationException
    {
        this.supportedFeatures = supportedFeatures;
        if (!hasBundleSupport()) {
            throw new UnsupportedFeatureConfigurationException("Participant doesn't support bundle, which is required");
        }
    }

    /**
     * Extracts and stores transport information from given map of Jingle
     * content.  If we already have the transport information it will be
     * merged into the currently stored one with
     * {@link TransportSignaling#mergeTransportExtension}.
     *
     * @param contents the list of <tt>ContentPacketExtension</tt> from one of
     * jingle message which can potentially contain transport info like
     * 'session-accept', 'transport-info', 'transport-accept' etc.
     */
    public void addTransportFromJingle(List<ContentPacketExtension> contents)
    {
        // Select first transport
        IceUdpTransportPacketExtension transport = null;
        for (ContentPacketExtension cpe : contents)
        {
            IceUdpTransportPacketExtension contentTransport
                = cpe.getFirstChildOfType(
                        IceUdpTransportPacketExtension.class);
            if (contentTransport != null)
            {
                transport = contentTransport;
                break;
            }
        }
        if (transport == null)
        {
            logger.error( "No valid transport supplied in transport-update from " + getChatMember().getName());
            return;
        }

        if (!transport.isRtcpMux())
        {
            transport.addChildExtension(new RtcpmuxPacketExtension());
        }

        if (bundleTransport == null)
        {
            bundleTransport = transport;
        }
        else
        {
            TransportSignaling.mergeTransportExtension(bundleTransport, transport);
        }
    }

    /**
     * Returns 'bundled' transport information stored for this
     * <tt>Participant</tt>.
     * @return <tt>IceUdpTransportPacketExtension</tt> which describes 'bundled'
     *         transport of this participant or <tt>null</tt> either if it's not
     *         available yet or if 'non-bundled' transport is being used.
     */
    public IceUdpTransportPacketExtension getBundleTransport()
    {
        return bundleTransport;
    }

    /**
     * Clears any ICE transport information currently stored for this
     * participant.
     */
    public void clearTransportInfo()
    {
        bundleTransport = null;
    }

    /**
     * Returns the {@link org.jitsi.jicofo.JitsiMeetConferenceImpl.BridgeSession}
     * or <tt>null</tt>.
     */
    public JitsiMeetConferenceImpl.BridgeSession getBridgeSession()
    {
        return bridgeSession;
    }

    /**
     * Returns the endpoint ID for this participant in the videobridge(Colibri)
     * context.
     */
    public String getEndpointId()
    {
        return getEndpointId(roomMember);
    }

    /**
     * Returns the stats ID of the participant.
     * @return the stats ID of the participant.
     */
    public String getStatId()
    {
        return roomMember.getStatsId();
    }

    /**
     * Returns the MUC JID of this <tt>Participant</tt>.
     * @return full MUC address e.g. "room1@muc.server.net/nickname"
     */
    public EntityFullJid getMucJid()
    {
        return roomMember.getOccupantJid();
    }

    public void claimSources(MediaSourceMap sourceMap)
    {
        // Mark as source owner
        Jid roomJid = roomMember.getOccupantJid();

        sourceMap
            .getMediaTypes()
            .forEach(
                mediaType -> sourceMap
                    .getSourcesForMedia(mediaType)
                    .forEach(
                        source -> SSRCSignaling.setSSRCOwner(source, roomJid)));
    }

    /**
     * @return {@code true} if the Jingle session with this participant has
     * been established.
     */
    @Override
    public boolean isSessionEstablished()
    {
        return jingleSession != null;
    }

    /**
     * Terminates the current {@code BridgeSession}, terminates the channel
     * allocator and resets any fields related to the session.
     *
     * @return {@code BridgeSession} from which this {@code Participant} has
     * been removed or {@code null} if this {@link Participant} was not part
     * of any bridge session.
     * @see org.jitsi.protocol.xmpp.colibri.ColibriConference#expireChannels(ColibriConferenceIQ)
     */
    JitsiMeetConferenceImpl.BridgeSession terminateBridgeSession()
    {
        JitsiMeetConferenceImpl.BridgeSession _session = this.bridgeSession;

        if (_session != null)
        {
            this.setChannelAllocator(null);
            _session.terminate(this);
            this.clearTransportInfo();
            this.setColibriChannelsInfo(null);
            this.bridgeSession = null;
        }

        return _session;
    }

    /**
     * Changes participant muted state by media type.
     * @param mediaType the media type to change.
     */
    public void setMuted(MediaType mediaType, boolean value)
    {
        this.mutedByMediaType.put(mediaType, value);

        ColibriConferenceIQ colibriChannelsInfo = this.getColibriChannelsInfo();
        if (colibriChannelsInfo != null)
        {
            ColibriConferenceIQ.Content content = colibriChannelsInfo.getContent(mediaType.toString());

            if (content != null)
            {
                content.getChannels().forEach(ch -> ch.setDirection(value ? "sendonly" : "sendrecv"));
            }
        }
    }

    /**
     * Checks whether the participant is muted.
     * @param mediaType the media type to check.
     * @return tru if it is muted.
     */
    public boolean isMuted(MediaType mediaType)
    {
        Boolean value = this.mutedByMediaType.get(mediaType);
        return value != null && value;
    }

    @Override
    public String toString()
    {
        return "Participant[" + getMucJid() + "]@" + hashCode();
    }

}

class UnsupportedFeatureConfigurationException extends Exception
{
    public UnsupportedFeatureConfigurationException(String msg)
    {
        super(msg);
    }
}
