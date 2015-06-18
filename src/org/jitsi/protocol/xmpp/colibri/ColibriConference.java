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
package org.jitsi.protocol.xmpp.colibri;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import net.java.sip.communicator.service.protocol.*;

import org.jitsi.jicofo.*;
import org.jitsi.protocol.*;
import org.jitsi.protocol.xmpp.util.*;

import java.util.*;

/**
 * This is Colibri conference allocated on the videobridge. It exposes
 * operations like allocating/expiring channels, updating channel transport
 * and so on.
 *
 * @author Pawel Domas
 */
public interface ColibriConference
{
    /**
     * Sets Jitsi videobridge XMPP address to be used to allocate
     * the conferences.
     *
     * @param videobridgeJid the videobridge address to be set.
     */
    void setJitsiVideobridge(String videobridgeJid);

    /**
     * Returns XMPP address of currently used videobridge or <tt>null</tt>
     * if the isn't any.
     */
    String getJitsiVideobridge();

    /**
     * Returns the identifier assigned for our conference by the videobridge.
     * Will returns <tt>null</tt> if no conference has been allocated yet for
     * this instance.
     */
    String getConferenceId();

    /**
     * Sets Jitsi Meet config that provides Colibri channels configurable
     * properties.
     * @param config <tt>JitsiMeetConfig</tt> to be used for allocating
     *               Colibri channels in this conference.
     */
    public void setConfig(JitsiMeetConfig config);

    /**
     * Returns <tt>true</tt> if conference has been allocated during last
     * allocate channels request. Method is synchronized and will return
     * <tt>true</tt> only for the first time is called, so that only one thread
     * will get positive value. That is because there are multiple threads
     * allocating channels on conference start and all of them will have
     * conference ID == null before operation, so it can't be used to detect
     * conference created event.
     */
    public boolean hasJustAllocated();

    /**
     * Creates channels on the videobridge for given parameters.
     *
     * @param useBundle <tt>true</tt> if channel transport bundle should be used
     *                  for this allocation.
     * @param endpointName the name that will identify channels endpoint.
     * @param peerIsInitiator <tt>true</tt> if peer is ICE an initiator
     *                        of ICE session.
     * @param contents content list that describes peer media.
     * @return <tt>ColibriConferenceIQ</tt> that describes allocated channels.
     *
     * @throws OperationFailedException if channel allocation failed due to
     *                                  network or bridge failure.
     */
    ColibriConferenceIQ createColibriChannels(
        boolean useBundle,
        String endpointName,
        boolean peerIsInitiator,
        List<ContentPacketExtension> contents)
        throws OperationFailedException;

    /**
     * Updates the RTP description for active channels (existing on the bridge).
     *
     * @param map the map of content name to RTP description packet extension.
     * @param localChannelsInfo <tt>ColibriConferenceIQ</tt> that contains
     * the description of the channel for which the RTP description will be
     * updated on the bridge.
     */
    void updateRtpDescription(
            Map<String, RtpDescriptionPacketExtension> map,
            ColibriConferenceIQ localChannelsInfo);

    /**
     * Updates transport information for active channels
     * (existing on the bridge).
     *
     * @param initiator <tt>true</tt> if peer is the initiator of ICE session.
     * @param map the map of content name to transport packet extension.
     * @param localChannelsInfo <tt>ColibriConferenceIQ</tt> that contains
     *                          the description of the channel for which
     *                          transport information will be updated
     *                          on the bridge.
     */
    void updateTransportInfo(
        boolean initiator,
        Map<String, IceUdpTransportPacketExtension> map,
        ColibriConferenceIQ localChannelsInfo);

    /**
     * Updates simulcast layers on the bridge.
     * @param ssrcGroups the map of media SSRC groups that will be updated on
     *                   the bridge.
     * @param localChannelsInfo <<tt>ColibriConferenceIQ</tt> that contains
     *                          the description of the channel for which
     *                          SSRC groups information will be updated
     *                          on the bridge.</tt>
     */
    void updateSourcesInfo(
        MediaSSRCMap ssrcs,
        MediaSSRCGroupMap ssrcGroups,
        ColibriConferenceIQ localChannelsInfo);

    /**
     * Updates channel bundle transport information for channels described by
     * <tt>localChannelsInfo</tt>. Single transport is set on the bundle shared
     * by all channels described by given IQ and only one bundle group can be
     * updated by single call to this method.
     *
     * @param initiator <tt>true</tt> if peer is the initiator of ICE session.
     * @param transport the transport packet extension that contains channel
     *                  bundle transport candidates.
     * @param localChannelsInfo <tt>ColibriConferenceIQ</tt> that contains
     *                          the description of the channels sharing the same
     *                          bundle group.
     */
    void updateBundleTransportInfo(
        boolean initiator,
        IceUdpTransportPacketExtension transport,
        ColibriConferenceIQ localChannelsInfo);

    /**
     * Expires the channels described by given <tt>ColibriConferenceIQ</tt>.
     *
     * @param channelInfo the <tt>ColibriConferenceIQ</tt> that contains
     *                    information about the channel to be expired.
     */
    void expireChannels(ColibriConferenceIQ channelInfo);

    /**
     * Expires all channels in current conference and resets conference state.
     */
    void expireConference();

    /**
     * Mutes audio channels described in given IQ by changing their media
     * direction to {@link org.jitsi.service.neomedia.MediaDirection#SENDONLY}.
     * @param channelsInfo the IQ that describes the channels to be muted.
     * @param mute <tt>true</tt> to mute or <tt>false</tt> to unmute audio
     *             channels described in <tt>channelsInfo</tt>.
     * @return <tt>true</tt> if the operation has succeeded or <tt>false</tt>
     *         otherwise.
     */
    boolean muteParticipant(ColibriConferenceIQ channelsInfo, boolean mute);
}
