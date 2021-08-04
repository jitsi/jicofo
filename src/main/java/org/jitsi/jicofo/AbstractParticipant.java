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

import java.util.*;

import org.jetbrains.annotations.*;
import org.jitsi.jicofo.conference.source.*;
import org.jitsi.xmpp.extensions.colibri.*;
import org.jitsi.xmpp.extensions.jingle.*;
import org.jitsi.utils.logging2.*;

/**
 * Represents an entity in a {@link JitsiMeetConferenceImpl} which has
 * associated Colibri channels, and a set of SSRCs described as "sources"
 * and "source groups". This can be associated either with an actual participant
 * in the conference (which has a chat room member, and a Jingle session), or
 * a bridge-to-bridge (Octo) channel on a particular bridge instance.
 *
 * @author Pawel Domas
 * @author Boris Grozev
 */
public abstract class AbstractParticipant
{
    /**
     * Information about Colibri channels allocated for this peer (if any).
     */
    private ColibriConferenceIQ colibriChannelsInfo;

    /**
     * The map of the most recently received RTP description for each Colibri
     * content.
     */
    private Map<String, RtpDescriptionPacketExtension> rtpDescriptionMap;

    /**
     * Remote sources that have been added to the conference, but not yet been signaled to this participant. They are to
     * be signaled once the Jingle session is initiated.
     */
    @NotNull
    private ConferenceSourceMap pendingRemoteSourcesToAdd = new ConferenceSourceMap();

    /**
     * Remote sources that have been removed from the conference, but have already been signaled to this participant.
     * Their removal is to be signaled once the Jingle session is initiated.
     *
     * Note that if a source is added and then removed while the jingle session is initiating it will be present in both
     * {@link #pendingRemoteSourcesToAdd} and {@link #pendingRemoteSourcesToRemove}. As a result we'll unnecessarily
     * send a source-add followed by a source-remove for this source. This is a rare case, so the inefficiency is
     * acceptable.
     */
    @NotNull
    private ConferenceSourceMap pendingRemoteSourcesToRemove = new ConferenceSourceMap();

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
     * Used to synchronize access to {@link #channelAllocator}.
     */
    private final Object channelAllocatorSyncRoot = new Object();

    /**
     * The {@link AbstractChannelAllocator}, if any, which is currently
     * allocating channels for this participant.
     */
    private AbstractChannelAllocator channelAllocator = null;

    private final Logger logger;

    protected AbstractParticipant(Logger conferenceLogger)
    {
        this.logger = new LoggerImpl(getClass().getName(), conferenceLogger.getLevel());
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
     * Gets a read-only view of the sources advertised by this participant.
     */
    public abstract ConferenceSourceMap getSources();

    /**
     * Clear the pending remote sources, indicating that they have now been signaled.
     */
    public void clearPendingRemoteSources()
    {
        pendingRemoteSourcesToAdd = new ConferenceSourceMap();
        pendingRemoteSourcesToRemove = new ConferenceSourceMap();
    }

    /**
     * Returns the set of remote sources which are yet to be signaled to this participant.
     */
    public ConferenceSourceMap getPendingRemoteSourcesToAdd()
    {
        return pendingRemoteSourcesToAdd.unmodifiable();
    }

    /**
     * Returns set of remote sources whose removal has yet to be signaled to this participant.
     */
    public ConferenceSourceMap getPendingRemoteSourcesToRemove()
    {
        return pendingRemoteSourcesToRemove.unmodifiable();
    }

    /**
     * Adds sources to the set of remote sources which haven't been signaled yet.
     *
     * @param sourcesToAdd the remote sources to add to the set of sources which are yet to be signaled.
     */
    public void addPendingRemoteSourcesToAdd(ConferenceSourceMap sourcesToAdd)
    {
        pendingRemoteSourcesToAdd.add(sourcesToAdd);
    }

    /**
     * Adds sources to the set of remote sources whose removal hasn't been signaled yet.
     *
     * @param sourcesToAdd the remote sources to add to the set of sources whose removal is yet to be signaled.
     */
    public void addPendingRemoteSourcesToRemove(ConferenceSourceMap sourcesToAdd)
    {
        pendingRemoteSourcesToRemove.add(sourcesToAdd);
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
     * Replaces the {@link AbstractChannelAllocator}, which is currently
     * allocating channels for this participant (if any) with the specified
     * channel allocator (if any).
     * @param channelAllocator the channel allocator to set, or {@code null}
     * to clear it.
     */
    public void setChannelAllocator(
        AbstractChannelAllocator channelAllocator)
    {
        synchronized (channelAllocatorSyncRoot)
        {
            if (this.channelAllocator != null)
            {
                // There is an ongoing thread allocating channels and sending
                // an invite for this participant. Tell it to stop.
                logger.warn("Canceling " + this.channelAllocator);
                this.channelAllocator.cancel();
            }

            this.channelAllocator = channelAllocator;
        }
    }

    /**
     * Signals to this {@link Participant} that a specific
     * {@link AbstractChannelAllocator} has completed its task and its thread
     * is about to terminate.
     * @param channelAllocator the {@link AbstractChannelAllocator} which has
     * completed its task and its thread is about to terminate.
     */
    void channelAllocatorCompleted(
        AbstractChannelAllocator channelAllocator)
    {
        synchronized (channelAllocatorSyncRoot)
        {
            if (this.channelAllocator == channelAllocator)
            {
                this.channelAllocator = null;
            }
        }
    }

    /**
     * @return {@code true} if the session with this participant has already
     * been established. Before the session is established, we are unable to
     * update the remote state (e.g. the list of sources (SSRCs) of this
     * participant).
     */
    abstract public boolean isSessionEstablished();
}
