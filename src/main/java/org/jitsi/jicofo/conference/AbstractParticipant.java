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

import java.util.*;

import com.google.common.collect.*;
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
     * List of remote source addition or removal operations that have not yet been signaled to this participant.
     */
    private final List<SourcesToAddOrRemove> queuedRemoteSourceChanges = new ArrayList<>();

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
        this.logger = conferenceLogger.createChildLogger(getClass().getName());
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
     * @return the list of source addition or removal which have been queueed and not signaled to this participant.
     */
    public List<SourcesToAddOrRemove> clearQueuedRemoteSourceChanges()
    {
        synchronized (queuedRemoteSourceChanges)
        {
            List<SourcesToAddOrRemove> ret = new ArrayList<>(queuedRemoteSourceChanges);
            queuedRemoteSourceChanges.clear();
            return ret;
        }
    }

    /**
     * Gets the list of pending remote sources, without clearing them. For testing.
     */
    public List<SourcesToAddOrRemove> getQueuedRemoteSourceChanges()
    {
        synchronized (queuedRemoteSourceChanges)
        {
            return new ArrayList<>(queuedRemoteSourceChanges);
        }
    }

    /**
     * Queue a "source-add" for remote sources, to be signaled once the session is established.
     *
     * @param sourcesToAdd the remote sources for the "source-add".
     */
    public void queueRemoteSourcesToAdd(ConferenceSourceMap sourcesToAdd)
    {
        synchronized (queuedRemoteSourceChanges)
        {
            SourcesToAddOrRemove previous = Iterables.getLast(queuedRemoteSourceChanges, null);
            if (previous != null && previous.getAction() == AddOrRemove.Add)
            {
                // We merge sourcesToAdd with the previous sources queued to be added to reduce the number of
                // source-add messages that need to be sent.
                queuedRemoteSourceChanges.remove(queuedRemoteSourceChanges.size() - 1);
                sourcesToAdd = sourcesToAdd.copy();
                sourcesToAdd.add(previous.getSources());
            }

            queuedRemoteSourceChanges.add(new SourcesToAddOrRemove(AddOrRemove.Add, sourcesToAdd));
        }
    }

    /**
     * Queue a "source-remove" for remote sources, to be signaled once the session is established.
     *
     * @param sourcesToRemove the remote sources for the "source-remove".
     */
    public void queueRemoteSourcesToRemove(ConferenceSourceMap sourcesToRemove)
    {
        synchronized (queuedRemoteSourceChanges)
        {
            SourcesToAddOrRemove previous = Iterables.getLast(queuedRemoteSourceChanges, null);
            if (previous != null && previous.getAction() == AddOrRemove.Remove)
            {
                // We merge sourcesToRemove with the previous sources queued to be remove to reduce the number of
                // source-remove messages that need to be sent.
                queuedRemoteSourceChanges.remove(queuedRemoteSourceChanges.size() - 1);
                sourcesToRemove = sourcesToRemove.copy();
                sourcesToRemove.add(previous.getSources());
            }

            queuedRemoteSourceChanges.add(new SourcesToAddOrRemove(AddOrRemove.Remove, sourcesToRemove));
        }
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
