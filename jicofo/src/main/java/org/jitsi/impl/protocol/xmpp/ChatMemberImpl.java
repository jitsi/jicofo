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
package org.jitsi.impl.protocol.xmpp;

import org.jetbrains.annotations.*;
import org.jitsi.jicofo.conference.source.*;
import org.jitsi.jicofo.xmpp.*;
import org.jitsi.jicofo.xmpp.muc.*;
import org.jitsi.utils.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.xmpp.extensions.jitsimeet.*;

import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smackx.muc.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.parts.*;

import java.util.*;

/**
 * Stripped Smack implementation of {@link ChatRoomMember}.
 *
 * @author Pawel Domas
 */
public class ChatMemberImpl
    implements ChatRoomMember
{
    /**
     * The logger
     */
    private final Logger logger;

    /**
     * The resource part of this {@link ChatMemberImpl}'s JID in the MUC.
     */
    @NotNull
    private final Resourcepart resourcepart;

    /**
     * The region (e.g. "us-east") of this {@link ChatMemberImpl}, advertised
     * by the remote peer in presence.
     */
    private String region;

    /**
     * The chat room of the member.
     */
    private final ChatRoomImpl chatRoom;

    /**
     * Join order number
     */
    private final int joinOrderNumber;

    /**
     * Full MUC address:
     * room_name@muc.server.net/nickname
     */
    @NotNull
    private final EntityFullJid occupantJid;

    /**
     * Caches real JID of the participant if we're able to see it (not the MUC
     * address stored in {@link ChatMemberImpl#occupantJid}).
     */
    private Jid jid = null;

    /**
     * Stores the last <tt>Presence</tt> processed by this
     * <tt>ChatMemberImpl</tt>.
     */
    private Presence presence;

    /**
     * Indicates whether or not this MUC member is a robot.
     */
    private boolean robot = false;

    private boolean isJigasi = false;
    private boolean isJibri = false;

    private MemberRole role;

    /**
     * Stores statistics ID for the member.
     */
    private String statsId;

    /**
     * Indicates whether the member's audio sources are currently muted.
     */
    private boolean isAudioMuted = true;

    /**
     * Indicates whether the member's video sources are currently muted.
     */
    private boolean isVideoMuted = true;

    @NotNull
    private Set<SourceInfo> sourceInfos = Collections.emptySet();

    public ChatMemberImpl(EntityFullJid fullJid, ChatRoomImpl chatRoom, Logger parentLogger,
                          int joinOrderNumber)
    {
        this.occupantJid = fullJid;
        this.resourcepart = fullJid.getResourceOrThrow();
        this.chatRoom = chatRoom;
        this.joinOrderNumber = joinOrderNumber;
        this.logger = parentLogger.createChildLogger(getClass().getName());
        logger.addContext("occupant", resourcepart.toString());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Presence getPresence()
    {
        return presence;
    }

    public EntityFullJid getOccupantJid()
    {
        return occupantJid;
    }

    /**
     * @return the resource part of the MUC JID of this {@link ChatMemberImpl}
     * as a string.
     */
    @Override
    public String getName()
    {
        return resourcepart.toString();
    }

    @Override
    public MemberRole getRole()
    {
        if (this.role == null)
        {
            Occupant o = chatRoom.getOccupant(this);

            if (o == null)
            {
                return MemberRole.GUEST;
            }
            else
            {
                this.role = MemberRole.fromSmack(o.getRole(), o.getAffiliation());
            }
        }
        return this.role;
    }

    /**
     * Reset cached user role so that it will be refreshed when {@link
     * #getRole()} is called.
     */
    void resetCachedRole()
    {
        this.role = null;
    }

    @Override
    public void setRole(MemberRole role)
    {
        throw new RuntimeException("Not implemented yet.");
    }

    @Override
    public Jid getJid()
    {
        if (jid == null)
        {
            jid = chatRoom.getJid(occupantJid);
        }
        return jid;
    }

    @Override
    public int getJoinOrderNumber()
    {
        return joinOrderNumber;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isRobot()
    {
        // Jigasi and Jibri do not use the "robot" signaling, but semantically they should be considered "robots".
        return robot || isJigasi || isJibri;
    }

    @Override
    public boolean isJigasi()
    {
        return isJigasi;
    }

    @Override
    public boolean isJibri()
    {
        return isJibri;
    }

    @Override
    public boolean isAudioMuted() { return isAudioMuted; }

    @Override
    public boolean isVideoMuted() { return isVideoMuted; }

    private void setSourceInfo(String sourceInfoString)
    {
        Set<SourceInfo> sourceInfos;
        try
        {
            sourceInfos = SourceInfoKt.parseSourceInfoJson(sourceInfoString);
        }
        catch (Exception e)
        {
            logger.warn("Ignoring invalid SourceInfo JSON", e);
            return;
        }

        this.sourceInfos = sourceInfos;
    }

    @Override
    @NotNull
    public Set<SourceInfo> getSourceInfos()
    {
        return sourceInfos;
    }

    /**
     * Does presence processing.
     *
     * @param presence the instance of <tt>Presence</tt> packet extension sent
     *                 by this chat member.
     *
     * @throws IllegalArgumentException if given <tt>Presence</tt> does not
     *         belong to this <tt>ChatMemberImpl</tt>.
     */
    void processPresence(Presence presence)
    {
        if (!occupantJid.equals(presence.getFrom()))
        {
            throw new IllegalArgumentException(
                    String.format("Presence for another member: %s, my jid: %s",
                                  presence.getFrom(), occupantJid));
        }

        this.presence = presence;

        UserInfoPacketExt userInfoPacketExt
            = presence.getExtension(UserInfoPacketExt.class);
        if (userInfoPacketExt != null)
        {
            Boolean newStatus = userInfoPacketExt.isRobot();
            if (newStatus != null && this.robot != newStatus)
            {
                logger.debug(() -> "robot: " + robot);

                this.robot = newStatus;
            }
        }

        StandardExtensionElement sourceInfo = presence.getExtension("SourceInfo", "jabber:client");
        setSourceInfo(sourceInfo == null ? "{}" : sourceInfo.getText());

        // We recognize jigasi by the existence of a "feature" extension in its presence.
        FeaturesExtension features = presence.getExtension(FeaturesExtension.class);
        if (features != null)
        {
            isJigasi = features.getFeatureExtensions().stream().anyMatch(
                    feature -> "http://jitsi.org/protocol/jigasi".equals(feature.getVar()));

            if (features.getFeatureExtensions().stream().anyMatch(
                    feature -> "http://jitsi.org/protocol/jibri".equals(feature.getVar())))
            {
                if (isJidTrusted())
                {
                    isJibri = true;
                }
                else
                {
                    Jid domain = getJid() == null ? null : getJid().asDomainBareJid();
                    logger.warn("Jibri signaled from a non-trusted domain: " + domain +
                            ". The domain can be configured as trusted with the jicofo.xmpp.trusted-domains property.");
                    isJibri = false;
                }
            }
        }
        else
        {
            isJigasi = false;
            isJibri = false;
        }

        JitsiParticipantRegionPacketExtension regionPE
            = presence.getExtension(JitsiParticipantRegionPacketExtension.class);
        if (regionPE != null)
        {
            region = regionPE.getRegionId();
        }

        StartMutedPacketExtension ext
            = presence.getExtension(StartMutedPacketExtension.class);

        if (ext != null)
        {
            boolean[] startMuted = { ext.getAudioMuted(), ext.getVideoMuted() };

            // XXX Is this intended to be allowed for moderators or not?
            if (MemberRoleKt.hasAdministratorRights(getRole()))
            {
                chatRoom.setStartMuted(startMuted);
            }
        }

        StatsId statsIdPacketExt = presence.getExtension(StatsId.class);
        if (statsIdPacketExt != null)
        {
            statsId = statsIdPacketExt.getStatsId();
        }

        boolean wasAudioMuted = isAudioMuted;
        AudioMutedExtension audioMutedExt = presence.getExtension(AudioMutedExtension.class);
        isAudioMuted = audioMutedExt == null || audioMutedExt.isAudioMuted(); /* defaults to true */

        if (isAudioMuted != wasAudioMuted)
        {
            logger.debug(() -> "isAudioMuted = " + isAudioMuted);
            if (isAudioMuted)
                chatRoom.removeAudioSender();
            else
                chatRoom.addAudioSender();
        }

        boolean wasVideoMuted = isVideoMuted;
        VideoMutedExtension videoMutedExt = presence.getExtension(VideoMutedExtension.class);
        isVideoMuted = videoMutedExt == null || videoMutedExt.isVideoMuted(); /* defaults to true */

        if (isVideoMuted != wasVideoMuted)
        {
            logger.debug(() -> "isVideoMuted = " + isVideoMuted);
            if (isVideoMuted)
                chatRoom.removeVideoSender();
            else
                chatRoom.addVideoSender();
        }
    }

    /**
     * Whether this member is a trusted entity (logged in to one of the pre-configured trusted domains).
     */
    private boolean isJidTrusted()
    {
        Jid jid = getJid();
        return jid != null && XmppConfig.config.getTrustedDomains().contains(jid.asDomainBareJid());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getRegion()
    {
        return region;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getStatsId()
    {
        return statsId;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString()
    {
        return String.format("ChatMember[%s, jid: %s]@%s", occupantJid, jid, hashCode());
    }

    @NotNull
    public OrderedJsonObject getDebugState()
    {
        OrderedJsonObject o = new OrderedJsonObject();
        o.put("resourcepart", resourcepart.toString());
        o.put("region", String.valueOf(region));
        o.put("join_order_number", joinOrderNumber);
        o.put("occupant_jid", occupantJid.toString());
        o.put("jid", String.valueOf(jid));
        o.put("robot", robot);
        o.put("is_jibri", isJibri);
        o.put("is_jigasi", isJigasi);
        o.put("role", String.valueOf(role));
        o.put("stats_id", String.valueOf(statsId));
        o.put("is_audio_muted", isAudioMuted);
        o.put("is_video_muted", isVideoMuted);

        return o;
    }
}
