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

import java.util.*;
import org.jitsi.xmpp.extensions.colibri.*;
import org.jitsi.xmpp.extensions.jingle.*;
import org.jitsi.protocol.xmpp.util.*;
import org.jitsi.utils.logging.*;

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
     * The class logger which can be used to override logging level inherited
     * from {@link JitsiMeetConference}.
     */
    private final static Logger classLogger
        = Logger.getLogger(AbstractParticipant.class);

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
     * Peer's media sources.
     */
    protected final MediaSourceMap sources = new MediaSourceMap();

    /**
     * Peer's media source groups.
     */
    protected final MediaSourceGroupMap sourceGroups = new MediaSourceGroupMap();

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
     * Tells how many unique sources per media participant is allowed to advertise
     */
    protected int maxSourceCount = -1;

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

    /**
     * The logger for this instance. Uses the logging level either of the
     * {@link #classLogger} or {@link JitsiMeetConference#getLogger()}
     * whichever is higher.
     */
    private final Logger logger;

    protected AbstractParticipant(Logger conferenceLogger)
    {
        this.logger = Logger.getLogger(classLogger, conferenceLogger);
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
     * Schedules sources received from other peer for future 'source-add'
     * update.
     *
     * @param sourceMap the media source map that contains sources for future
     * updates.
     */
    public void scheduleSourcesToAdd(MediaSourceMap sourceMap)
    {
        sourcesToAdd.add(sourceMap);
    }

    /**
     * Schedules sources received from other peer for future 'source-remove'
     * update.
     *
     * @param sourceMap the media source map that contains sources for future
     * updates.
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

    public void addSourcesAndGroups(MediaSourceMap         addedSources,
                                    MediaSourceGroupMap    addedGroups)
    {
        this.sources.add(addedSources);
        this.sourceGroups.add(addedGroups);
    }

    /**
     * @return {@code true} if the session with this participant has already
     * been established. Before the session is established, we are unable to
     * update the remote state (e.g. the list of sources (SSRCs) of this
     * participant).
     */
    abstract public boolean isSessionEstablished();
}
