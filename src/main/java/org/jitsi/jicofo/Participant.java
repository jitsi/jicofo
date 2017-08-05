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

import org.jitsi.jicofo.discovery.*;
import org.jitsi.protocol.xmpp.*;
import org.jitsi.protocol.xmpp.util.*;
import org.jitsi.util.*;
import org.jxmpp.jid.EntityFullJid;
import org.jxmpp.jid.Jid;
import org.jxmpp.jid.parts.Resourcepart;

import java.util.*;

/**
 * Class represent Jitsi Meet conference participant. Stores information about
 * Colibri channels allocated, Jingle session and media sources.
 *
 * @author Pawel Domas
 */
public class Participant
{
    /**
     * The class logger which can be used to override logging level inherited
     * from {@link JitsiMeetConference}.
     */
    private final static Logger classLogger
        = Logger.getLogger(Participant.class);

    /**
     * Returns the endpoint ID for a participant in the videobridge (Colibri)
     * context. This method can be used before <tt>Participant</tt> instance is
     * created for the <tt>ChatRoomMember</tt>.
     *
     * @param chatRoomMember XMPP MUC chat room member which represents a
     *                       <tt>Participant</tt>.
     */
    public static String getEndpointId(XmppChatMember chatRoomMember)
    {
        return chatRoomMember.getName(); // XMPP MUC Nickname
    }

    /**
     * MUC chat member of this participant.
     */
    private final XmppChatMember roomMember;

    /**
     * Jingle session (if any) established with this peer.
     */
    private JingleSession jingleSession;

    /**
     * Information about Colibri channels allocated for this peer (if any).
     */
    private ColibriConferenceIQ colibriChannelsInfo;

    /**
     * The logger for this instance. Uses the logging level either of the
     * {@link #classLogger} or {@link JitsiMeetConference#getLogger()}
     * whichever is higher.
     */
    private final Logger logger;

    /**
     * The map of the most recently received RTP description for each Colibri
     * content.
     */
    private Map<String, RtpDescriptionPacketExtension> rtpDescriptionMap;

    /**
     * Peer's media sources.
     */
    private final MediaSourceMap sources = new MediaSourceMap();

    /**
     * Peer's media source groups.
     */
    private final MediaSourceGroupMap sourceGroups = new MediaSourceGroupMap();

    /**
     * sources received from other peers scheduled for later addition, because
     * of the Jingle session not being ready at the point when sources appeared in
     * the conference.
     */
    private MediaSourceMap sourcesToAdd = new MediaSourceMap();

    /**
     * source groups received from other peers scheduled for later addition.
     * @see #sourcesToAdd
     */
    private MediaSourceGroupMap sourceGroupsToAdd = new MediaSourceGroupMap();

    /**
     * sources received from other peers scheduled for later removal, because
     * of the Jingle session not being ready at the point when sources appeared in
     * the conference.
     * FIXME: do we need that since these were never added ? - check
     */
    private MediaSourceMap sourcesToRemove = new MediaSourceMap();

    /**
     * source groups received from other peers scheduled for later removal.
     * @see #sourcesToRemove
     */
    private MediaSourceGroupMap sourceGroupsToRemove = new MediaSourceGroupMap();

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
     * Tells how many unique sources per media participant is allowed to advertise
     */
    private final int maxSourceCount;

    /**
     * Remembers participant's muted status.
     */
    private boolean mutedStatus;

    /**
     * Participant's display name.
     */
    private String displayName = null;

    /**
     * Used to synchronize access to {@link #channelAllocator}.
     */
    private final Object channelAllocatorSyncRoot = new Object();

    /**
     * The {@link ChannelAllocator}, if any, which is currently allocating
     * channels for this participant.
     */
    private ChannelAllocator channelAllocator = null;

    /**
     * Creates new {@link Participant} for given chat room member.
     *
     * @param roomMember the {@link XmppChatMember} that represent this
     *                   participant in MUC conference room.
     *
     * @param maxSourceCount how many unique sources per media this participant
     *                     instance will be allowed to advertise.
     */
    public Participant(JitsiMeetConference    conference,
                       XmppChatMember         roomMember,
                       int maxSourceCount)
    {
        Objects.requireNonNull(conference, "conference");

        this.roomMember = Objects.requireNonNull(roomMember, "roomMember");
        this.maxSourceCount = maxSourceCount;
        this.logger = Logger.getLogger(classLogger, conference.getLogger());
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
     * Removes given media sources from this peer state.
     * @param sourceMap the source map that contains the sources to be removed.
     * @return <tt>MediaSourceMap</tt> which contains sources removed from this map.
     */
    public MediaSourceMap removeSources(MediaSourceMap sourceMap)
    {
        return sources.remove(sourceMap);
    }

    /**
     * Returns deep copy of this peer's media source map.
     */
    public MediaSourceMap getSourcesCopy()
    {
        return sources.copyDeep();
    }

    /**
     * Returns deep copy of this peer's media source group map.
     */
    public MediaSourceGroupMap getSourceGroupsCopy()
    {
        return sourceGroups.copy();
    }

    /**
     * Returns <tt>true</tt> if this peer has any not synchronized sources
     * scheduled for addition.
     */
    public boolean hasSourcesToAdd()
    {
        return !sourcesToAdd.isEmpty() || !sourceGroupsToAdd.isEmpty();
    }

    /**
     * Reset the queue that holds not synchronized sources scheduled for future
     * addition.
     */
    public void clearSourcesToAdd()
    {
        sourcesToAdd = new MediaSourceMap();
        sourceGroupsToAdd = new MediaSourceGroupMap();
    }

    /**
     * Reset the queue that holds not synchronized sources scheduled for future
     * removal.
     */
    public void clearSourcesToRemove()
    {
        sourcesToRemove = new MediaSourceMap();
        sourceGroupsToRemove = new MediaSourceGroupMap();
    }

    /**
     * Returns <tt>true</tt> if this peer has any not synchronized sources
     * scheduled for removal.
     */
    public boolean hasSourcesToRemove()
    {
        return !sourcesToRemove.isEmpty() || !sourceGroupsToRemove.isEmpty();
    }

    /**
     * Returns <tt>true</tt> if this peer has any not synchronized sources
     * scheduled for addition.
     */
    public MediaSourceMap getSourcesToAdd()
    {
        return sourcesToAdd;
    }

    /**
     * Returns <tt>true</tt> if this peer has any not synchronized sources
     * scheduled for removal.
     */
    public MediaSourceMap getSourcesToRemove()
    {
        return sourcesToRemove;
    }

    /**
     * Schedules sources received from other peer for future 'source-add' update.
     *
     * @param sourceMap the media source map that contains sources for future updates.
     */
    public void scheduleSourcesToAdd(MediaSourceMap sourceMap)
    {
        sourcesToAdd.add(sourceMap);
    }

    /**
     * Schedules sources received from other peer for future 'source-remove'
     * update.
     *
     * @param sourceMap the media source map that contains sources for future updates.
     */
    public void scheduleSourcesToRemove(MediaSourceMap sourceMap)
    {
        sourcesToRemove.add(sourceMap);
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
        this.supportedFeatures
            = Objects.requireNonNull(supportedFeatures, "supportedFeatures");
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
     * Returns the list of source groups of given media type that belong ot this
     * participant.
     * @param media the name of media type("audio","video", ...)
     * @return the list of {@link SourceGroup} for given media type.
     */
    public List<SourceGroup> getSourceGroupsForMedia(String media)
    {
        return sourceGroups.getSourceGroupsForMedia(media);
    }

    /**
     * Returns <tt>MediaSourceGroupMap</tt> that contains the mapping of media
     * source groups that describe media of this participant.
     */
    public MediaSourceGroupMap getSourceGroups()
    {
        return sourceGroups;
    }

    /**
     * Adds sources and source groups for media described in given Jingle content
     * list.
     * @param contents the list of <tt>ContentPacketExtension</tt> that
     *                 describes media sources and source groups.
     * @return an array of two objects where first one is <tt>MediaSourceMap</tt>
     * contains the sources that have been added and the second one is
     * <tt>MediaSourceGroupMap</tt> with <tt>SourceGroup</tt>s added to this
     * participant.
     *
     * @throws InvalidSSRCsException if a critical problem has been found
     * with SSRC and SSRC groups. This <tt>Participant</tt>'s state remains
     * unchanged (no SSRCs or groups were added/removed).
     */
    public Object[] addSourcesAndGroupsFromContent(
            List<ContentPacketExtension> contents)
        throws InvalidSSRCsException
    {
        SSRCValidator validator
            = new SSRCValidator(
                    getEndpointId(),
                    this.sources, this.sourceGroups, maxSourceCount, this.logger);

        MediaSourceMap sourcesToAdd
            = MediaSourceMap.getSourcesFromContent(contents);
        MediaSourceGroupMap groupsToAdd
            = MediaSourceGroupMap.getSourceGroupsForContents(contents);

        Object[] added
            = validator.tryAddSourcesAndGroups(sourcesToAdd, groupsToAdd);
        MediaSourceMap addedSources = (MediaSourceMap) added[0];
        MediaSourceGroupMap addedGroups = (MediaSourceGroupMap) added[1];

        // Mark as source owner
        Jid roomJid = roomMember.getJabberID();
        for (String mediaType : addedSources.getMediaTypes())
        {
            List<SourcePacketExtension> sources
                = addedSources.getSourcesForMedia(mediaType);
            for (SourcePacketExtension source : sources)
            {
                SSRCSignaling.setSSRCOwner(source, roomJid);
            }
        }

        this.sources.add(addedSources);
        this.sourceGroups.add(addedGroups);

        return added;
    }

    /**
     * Schedules given media source groups for later addition.
     * @param sourceGroups the <tt>MediaSourceGroupMap</tt> to be scheduled for
     *                   later addition.
     */
    public void scheduleSourceGroupsToAdd(MediaSourceGroupMap sourceGroups)
    {
        sourceGroupsToAdd.add(sourceGroups);
    }

    /**
     * Schedules given media source groups for later removal.
     * @param sourceGroups the <tt>MediaSourceGroupMap</tt> to be scheduled for
     *                   later removal.
     */
    public void scheduleSourceGroupsToRemove(MediaSourceGroupMap sourceGroups)
    {
        sourceGroupsToRemove.add(sourceGroups);
    }

    /**
     * Returns the map of source groups that are waiting for synchronization.
     */
    public MediaSourceGroupMap getSourceGroupsToAdd()
    {
        return sourceGroupsToAdd;
    }

    /**
     * Returns the map of source groups that are waiting for being removed from
     * peer session.
     */
    public MediaSourceGroupMap getSourceGroupsToRemove()
    {
        return sourceGroupsToRemove;
    }

    /**
     * Removes source groups from this participant state.
     * @param groupsToRemove the map of source groups that will be removed
     *                       from this participant media state description.
     * @return <tt>MediaSourceGroupMap</tt> which contains source groups removed
     *         from this map.
     */
    public MediaSourceGroupMap removeSourceGroups(MediaSourceGroupMap groupsToRemove)
    {
        return sourceGroups.remove(groupsToRemove);
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
    public EntityFullJid getMucJid()
    {
        return roomMember.getJabberID().asFullJidOrThrow();
    }

    /**
     * Replaces the {@link ChannelAllocator}, which is currently allocating
     * channels for this participant (if any) with the specified channel
     * allocator (if any).
     * @param channelAllocator the channel allocator to set, or {@code null}
     * to clear it.
     */
    public void setChannelAllocator(ChannelAllocator channelAllocator)
    {
        synchronized (channelAllocatorSyncRoot)
        {
            if (this.channelAllocator != null)
            {
                // There is an ongoing thread allocating channels and sending
                // an invite for this participant. Tell it to stop.
                this.channelAllocator.cancel();
                logger.warn("Canceling a ChannelAllocator.");
            }

            this.channelAllocator = channelAllocator;
        }
    }

    /**
     * Signals to this {@link Participant} that a specific
     * {@link ChannelAllocator} has completed its task and its thread is about
     * to terminate.
     * @param channelAllocator the {@link ChannelAllocator} which has completed
     * its task and its thread is about to terminate.
     */
    void channelAllocatorCompleted(ChannelAllocator channelAllocator)
    {
        synchronized (channelAllocatorSyncRoot)
        {
            if (this.channelAllocator == channelAllocator)
            {
                this.channelAllocator = null;
            }
        }
    }
}
