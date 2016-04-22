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
import net.java.sip.communicator.service.protocol.*;
import net.java.sip.communicator.util.*;

import org.jitsi.assertions.*;
import org.jitsi.jicofo.discovery.*;
import org.jitsi.protocol.xmpp.*;
import org.jitsi.protocol.xmpp.util.*;

import java.util.*;

/**
 * Class represent Jitsi Meet conference participant. Stores information about
 * Colibri channels allocated, Jingle session and media SSRCs.
 *
 * @author Pawel Domas
 */
public class Participant
{
    /**
     * The logger
     */
    private final static Logger logger = Logger.getLogger(Participant.class);

    /**
     * MUC chat member of this participant.
     */
    private final XmppChatMember roomMember;

    /**
     * Jingle session(if any) established with this peer.
     */
    private JingleSession jingleSession;

    /**
     * Information about Colibri channels allocated for this peer(if any).
     */
    private ColibriConferenceIQ colibriChannelsInfo;

    /**
     * The map of the most recently received RTP description for each Colibri
     * content.
     */
    private Map<String, RtpDescriptionPacketExtension> rtpDescriptionMap;

    /**
     * Peer's media SSRCs.
     */
    private final MediaSSRCMap ssrcs = new MediaSSRCMap();

    /**
     * Peer's media SSRC groups.
     */
    private final MediaSSRCGroupMap ssrcGroups = new MediaSSRCGroupMap();

    /**
     * SSRCs received from other peers scheduled for later addition, because
     * of the Jingle session not being ready at the point when SSRCs appeared in
     * the conference.
     */
    private MediaSSRCMap ssrcsToAdd = new MediaSSRCMap();

    /**
     * SSRC groups received from other peers scheduled for later addition.
     * @see #ssrcsToAdd
     */
    private MediaSSRCGroupMap ssrcGroupsToAdd = new MediaSSRCGroupMap();

    /**
     * SSRCs received from other peers scheduled for later removal, because
     * of the Jingle session not being ready at the point when SSRCs appeared in
     * the conference.
     * FIXME: do we need that since these were never added ? - check
     */
    private MediaSSRCMap ssrcsToRemove = new MediaSSRCMap();

    /**
     * SSRC groups received from other peers scheduled for later removal.
     * @see #ssrcsToRemove
     */
    private MediaSSRCGroupMap ssrcGroupsToRemove = new MediaSSRCGroupMap();

    /**
     * Stores information about bundled transport if {@link #hasBundleSupport()}
     * returns <tt>true</tt>.
     */
    private IceUdpTransportPacketExtension bundleTransport;

    /**
     * Maps ICE transport information to the name of Colibri content. This is
     * "non-bundled" transport which is used when {@link #hasBundleSupport()}
     * returns <tt>false</tt>.
     */
    private Map<String, IceUdpTransportPacketExtension> transportMap
        = new HashMap<>();

    /**
     * The list of XMPP features supported by this participant. 
     */
    private List<String> supportedFeatures = new ArrayList<>();

    /**
     * Tells how many unique SSRCs per media participant is allowed to advertise
     */
    private final int maxSSRCCount;

    /**
     * Remembers participant's muted status.
     */
    private boolean mutedStatus;

    /**
     * Participant's display name.
     */
    private String displayName = null;

    /**
     * Returns the endpoint ID for a participant in the videobridge(Colibri)
     * context. This method can be used before <tt>Participant</tt> instance is
     * created for the <tt>ChatRoomMember</tt>.
     *
     * @param chatRoomMember XMPP MUC chat room member which represent a
     *                       <tt>Participant</tt>.
     */
    static public String getEndpointId(ChatRoomMember chatRoomMember)
    {
        return chatRoomMember.getName(); // XMPP MUC Nickname
    }

    /**
     * Creates new {@link Participant} for given chat room member.
     *
     * @param roomMember the {@link XmppChatMember} that represent this
     *                   participant in MUC conference room.
     *
     * @param maxSSRCCount how many unique SSRCs per media this participant
     *                     instance will be allowed to advertise.
     */
    public Participant(XmppChatMember roomMember, int maxSSRCCount)
    {
        Assert.notNull(roomMember, "roomMember");

        this.roomMember = roomMember;
        this.maxSSRCCount = maxSSRCCount;
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
     * Sets {@link JingleSession} established with this peer.
     * @param jingleSession the new Jingle session to be assigned to this peer.
     */
    public void setJingleSession(JingleSession jingleSession)
    {
        this.jingleSession = jingleSession;
    }

    /**
     * Returns {@link XmppChatMember} that represents this participant in
     * conference multi-user chat room.
     */
    public XmppChatMember getChatMember()
    {
        return roomMember;
    }

    /**
     * Returns currently stored map of RTP description to Colibri content name.
     * @return a <tt>Map<String,RtpDescriptionPacketExtension></tt> which maps
     *         the RTP descriptions to the corresponding Colibri content names.
     */
    public Map<String, RtpDescriptionPacketExtension> getRtpDescriptionMap()
    {
        return rtpDescriptionMap;
    }

    /**
     * Extracts and stores RTP description for each content type from given
     * Jingle contents.
     * @param jingleContents the list of Jingle content packet extension from
     *        <tt>Participant</tt>'s answer.
     */
    public void setRTPDescription(List<ContentPacketExtension> jingleContents)
    {
        Map<String, RtpDescriptionPacketExtension> rtpDescMap = new HashMap<>();

        for (ContentPacketExtension content : jingleContents)
        {
            RtpDescriptionPacketExtension rtpDesc
                = content.getFirstChildOfType(
                        RtpDescriptionPacketExtension.class);

            if (rtpDesc != null)
            {
                rtpDescMap.put(content.getName(), rtpDesc);
            }
        }

        this.rtpDescriptionMap = rtpDescMap;
    }

    /**
     * Imports media SSRCs from given list of <tt>ContentPacketExtension</tt>.
     *
     * @param contents the list that contains peer's media contents.
     *
     * @return <tt>MediaSSRCMap</tt> tha contains only the SSRCs that were
     *        actually added to this participant(which were not duplicated).
     */
    public MediaSSRCMap addSSRCsFromContent(
            List<ContentPacketExtension> contents)
    {
        // Configure SSRC owner in 'ssrc-info' with user's MUC Jid
        MediaSSRCMap ssrcsToAdd = MediaSSRCMap.getSSRCsFromContent(contents);

        MediaSSRCMap addedSSRCs = new MediaSSRCMap();

        for (String mediaType : ssrcsToAdd.getMediaTypes())
        {
            List<SourcePacketExtension> mediaSsrcs
                = ssrcsToAdd.getSSRCsForMedia(mediaType);

            for (SourcePacketExtension ssrcPe : mediaSsrcs)
            {
                SSRCSignaling.setSSRCOwner(
                    ssrcPe, roomMember.getContactAddress());

                long ssrcValue = ssrcPe.getSSRC();

                if (ssrcs.findSSRC(mediaType, ssrcValue) != null)
                {
                    logger.warn(
                        "Detected duplicated SSRC " + ssrcValue
                            + " signalled by " + getEndpointId());
                    continue;
                }
                else if (ssrcs.getSSRCsForMedia(mediaType).size()
                        >= maxSSRCCount)
                {
                    logger.warn(
                        "SSRC limit of " + maxSSRCCount + " exceeded by "
                            + getEndpointId() + " - dropping "
                            + mediaType + " SSRC: " + ssrcValue);
                    break;
                }

                ssrcs.addSSRC(mediaType, ssrcPe.copy());

                addedSSRCs.addSSRC(mediaType, ssrcPe);
            }
        }

        return addedSSRCs;
    }

    /**
     * Removes given media SSRCs from this peer state.
     * @param ssrcMap the SSRC map that contains the SSRCs to be removed.
     * @return <tt>MediaSSRCMap</tt> which contains SSRCs removed from this map.
     */
    public MediaSSRCMap removeSSRCs(MediaSSRCMap ssrcMap)
    {
        return ssrcs.remove(ssrcMap);
    }

    /**
     * Returns deep copy of this peer's media SSRC map.
     */
    public MediaSSRCMap getSSRCsCopy()
    {
        return ssrcs.copyDeep();
    }

    /**
     * Returns deep copy of this peer's media SSRC group map.
     */
    public MediaSSRCGroupMap getSSRCGroupsCopy()
    {
        return ssrcGroups.copy();
    }

    /**
     * Returns <tt>true</tt> if this peer has any not synchronized SSRCs
     * scheduled for addition.
     */
    public boolean hasSsrcsToAdd()
    {
        return !ssrcsToAdd.isEmpty() || !ssrcGroupsToAdd.isEmpty();
    }

    /**
     * Reset the queue that holds not synchronized SSRCs scheduled for future
     * addition.
     */
    public void clearSsrcsToAdd()
    {
        ssrcsToAdd = new MediaSSRCMap();
        ssrcGroupsToAdd = new MediaSSRCGroupMap();
    }

    /**
     * Reset the queue that holds not synchronized SSRCs scheduled for future
     * removal.
     */
    public void clearSsrcsToRemove()
    {
        ssrcsToRemove = new MediaSSRCMap();
        ssrcGroupsToRemove = new MediaSSRCGroupMap();
    }

    /**
     * Returns <tt>true</tt> if this peer has any not synchronized SSRCs
     * scheduled for removal.
     */
    public boolean hasSsrcsToRemove()
    {
        return !ssrcsToRemove.isEmpty() || !ssrcGroupsToRemove.isEmpty();
    }

    /**
     * Returns <tt>true</tt> if this peer has any not synchronized SSRCs
     * scheduled for addition.
     */
    public MediaSSRCMap getSsrcsToAdd()
    {
        return ssrcsToAdd;
    }

    /**
     * Returns <tt>true</tt> if this peer has any not synchronized SSRCs
     * scheduled for removal.
     */
    public MediaSSRCMap getSsrcsToRemove()
    {
        return ssrcsToRemove;
    }

    /**
     * Schedules SSRCs received from other peer for future 'source-add' update.
     *
     * @param ssrcMap the media SSRC map that contains SSRCs for future updates.
     */
    public void scheduleSSRCsToAdd(MediaSSRCMap ssrcMap)
    {
        ssrcsToAdd.add(ssrcMap);
    }

    /**
     * Schedules SSRCs received from other peer for future 'source-remove'
     * update.
     *
     * @param ssrcMap the media SSRC map that contains SSRCs for future updates.
     */
    public void scheduleSSRCsToRemove(MediaSSRCMap ssrcMap)
    {
        ssrcsToRemove.add(ssrcMap);
    }

    /**
     * Sets information about Colibri channels allocated for this participant.
     *
     * @param colibriChannelsInfo the IQ that holds colibri channels state.
     */
    public void setColibriChannelsInfo(ColibriConferenceIQ colibriChannelsInfo)
    {
        this.colibriChannelsInfo = colibriChannelsInfo;
    }

    /**
     * Returns {@link ColibriConferenceIQ} that describes Colibri channels
     * allocated for this participant.
     */
    public ColibriConferenceIQ getColibriChannelsInfo()
    {
        return colibriChannelsInfo;
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
     * Returns {@code true} iff this participant supports RTX.
     */
    public boolean hasRtxSupport()
    {
        return supportedFeatures.contains(DiscoveryUtil.FEATURE_RTX);
    }

    /**
     * FIXME: we need to remove "is SIP gateway code", but there are still 
     * situations where we need to know whether given peer is a human or not.
     * For example when we close browser window and only SIP gateway stays
     * we should destroy the conference and close SIP connection.
     *  
     * Returns <tt>true</tt> if this participant belongs to SIP gateway service.
     */
    public boolean isSipGateway()
    {
        return supportedFeatures.contains("http://jitsi.org/protocol/jigasi");
    }

    /**
     * Returns <tt>true</tt> if RTP audio is supported by this peer.
     */
    public boolean hasAudioSupport()
    {
        return supportedFeatures.contains(DiscoveryUtil.FEATURE_AUDIO);
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
    public void setSupportedFeatures(List<String> supportedFeatures)
    {
        Assert.notNull(supportedFeatures, "supportedFeatures");

        this.supportedFeatures = supportedFeatures;
    }

    /**
     * Sets muted status of this participant.
     * @param mutedStatus new muted status to set.
     */
    public void setMuted(boolean mutedStatus)
    {
        this.mutedStatus = mutedStatus;
    }

    /**
     * Returns <tt>true</tt> if this participant is muted or <tt>false</tt>
     * otherwise.
     */
    public boolean isMuted()
    {
        return mutedStatus;
    }

    /**
     * Return a <tt>Boolean</tt> which informs about this participant's video
     * muted status. The <tt>null</tt> value stands for 'unknown'/not signalled,
     * <tt>true</tt> for muted and <tt>false</tt> means unmuted.
     */
    public Boolean isVideoMuted()
    {
        return roomMember.hasVideoMuted();
    }

    /**
     * Returns the list of SSRC groups of given media type that belong ot this
     * participant.
     * @param media the name of media type("audio","video", ...)
     * @return the list of {@link SSRCGroup} for given media type.
     */
    public List<SSRCGroup> getSSRCGroupsForMedia(String media)
    {
        return ssrcGroups.getSSRCGroupsForMedia(media);
    }

    /**
     * Returns <tt>MediaSSRCGroupMap</tt> that contains the mapping of media
     * SSRC groups that describe media of this participant.
     */
    public MediaSSRCGroupMap getSSRCGroups()
    {
        return ssrcGroups;
    }

    /**
     * Adds SSRC groups for media described in given Jingle content list.
     * @param contents the list of <tt>ContentPacketExtension</tt> that
     *                 describes media SSRC groups.
     * @return <tt>MediaSSRCGroupMap</tt> with <tt>SSRCGroup</tt>s
     *         which were added to this participant.
     */
    public MediaSSRCGroupMap addSSRCGroupsFromContent(
            List<ContentPacketExtension> contents)
    {
        MediaSSRCGroupMap addedSsrcGroups
            = MediaSSRCGroupMap.getSSRCGroupsForContents(contents);

        return ssrcGroups.add(addedSsrcGroups.copy());
    }

    /**
     * Schedules given media SSRC groups for later addition.
     * @param ssrcGroups the <tt>MediaSSRCGroupMap</tt> to be scheduled for
     *                   later addition.
     */
    public void scheduleSSRCGroupsToAdd(MediaSSRCGroupMap ssrcGroups)
    {
        ssrcGroupsToAdd.add(ssrcGroups);
    }

    /**
     * Schedules given media SSRC groups for later removal.
     * @param ssrcGroups the <tt>MediaSSRCGroupMap</tt> to be scheduled for
     *                   later removal.
     */
    public void scheduleSSRCGroupsToRemove(MediaSSRCGroupMap ssrcGroups)
    {
        ssrcGroupsToRemove.add(ssrcGroups);
    }

    /**
     * Returns the map of SSRC groups that are waiting for synchronization.
     */
    public MediaSSRCGroupMap getSSRCGroupsToAdd()
    {
        return ssrcGroupsToAdd;
    }

    /**
     * Returns the map of SSRC groups that are waiting for being removed from
     * peer session.
     */
    public MediaSSRCGroupMap getSsrcGroupsToRemove()
    {
        return ssrcGroupsToRemove;
    }

    /**
     * Removes SSRC groups from this participant state.
     * @param groupsToRemove the map of SSRC groups that will be removed
     *                       from this participant media state description.
     * @return <tt>MediaSSRCGroupMap</tt> which contains SSRC groups removed
     *         from this map.
     */
    public MediaSSRCGroupMap removeSSRCGroups(MediaSSRCGroupMap groupsToRemove)
    {
        return ssrcGroups.remove(groupsToRemove);
    }

    /**
     * Extracts and stores transport information from given map of Jingle
     * content. Depending on the {@link #hasBundleSupport()} either 'bundle' or
     * 'non-bundle' transport information will be stored. If we already have the
     * transport information it will be merged into the currently stored one
     * with {@link TransportSignaling#mergeTransportExtension}.
     *
     * @param contents the list of <tt>ContentPacketExtension</tt> from one of
     * jingle message which can potentially contain transport info like
     * 'session-accept', 'transport-info', 'transport-accept' etc.
     */
    public void addTransportFromJingle(List<ContentPacketExtension> contents)
    {
        if (hasBundleSupport())
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
                logger.error(
                    "No valid transport supplied in transport-update from "
                        + getChatMember().getContactAddress());
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
                TransportSignaling.mergeTransportExtension(
                        bundleTransport, transport);
            }
        }
        else
        {
            for (ContentPacketExtension cpe : contents)
            {
                IceUdpTransportPacketExtension srcTransport
                    = cpe.getFirstChildOfType(
                            IceUdpTransportPacketExtension.class);

                if (srcTransport != null)
                {
                    String contentName = cpe.getName().toLowerCase();
                    IceUdpTransportPacketExtension dstTransport
                        = transportMap.get(contentName);

                    if (dstTransport == null)
                    {
                        transportMap.put(contentName, srcTransport);
                    }
                    else
                    {
                        TransportSignaling.mergeTransportExtension(
                                dstTransport, srcTransport);
                    }
                }
            }
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
     * Returns 'non-bundled' transport information stored for this
     * <tt>Participant</tt>.
     *
     * @return a map of <tt>IceUdpTransportPacketExtension</tt> to Colibri
     * content name which describes 'non-bundled' transport of this participant
     * or <tt>null</tt> either if it's not available yet or if 'bundled'
     * transport is being used.
     */
    public Map<String, IceUdpTransportPacketExtension> getTransportMap()
    {
        return transportMap;
    }

    /**
     * Clears any ICE transport information currently stored for this
     * participant.
     */
    public void clearTransportInfo()
    {
        bundleTransport = null;
        transportMap = new HashMap<>();
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
     * Returns the display name of the participant.
     * @return the display name of the participant.
     */
    public String getDisplayName()
    {
        return displayName;
    }

    /**
     * Sets the display name of the participant.
     * @param displayName the display name to set.
     */
    public void setDisplayName(String displayName)
    {
        this.displayName = displayName;
    }

    /**
     * Returns the MUC JID of this <tt>Participant</tt>.
     * @return full MUC address e.g. "room1@muc.server.net/nickname"
     */
    public String getMucJid()
    {
        return roomMember.getContactAddress();
    }
}
