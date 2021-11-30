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
package org.jitsi.jicofo.conference.colibri;

import com.google.common.collect.*;
import org.jitsi.jicofo.conference.*;
import org.jitsi.jicofo.conference.source.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.xmpp.extensions.colibri.*;
import org.jitsi.xmpp.extensions.jingle.*;
import org.jxmpp.jid.*;

import java.util.*;

/**
 * Implements a participant for Octo. Manages the colibri
 * channels used for Octo on a particular jitsi-videobridge instance, and
 * the sources and source groups which need to be added to these colibri
 * channels (i.e. all sources and source groups from real participants in the
 * conference on other bridges).
 *
 * @author Boris Grozev
 */
public class OctoParticipant
{
    /**
     * Information about Colibri channels allocated for this peer (if any).
     */
    private ColibriConferenceIQ colibriChannelsInfo;

    /**
     * List of remote source addition or removal operations that have not yet been signaled to this participant.
     */
    private final List<SourcesToAddOrRemove> queuedRemoteSourceChanges = new ArrayList<>();

    /**
     * Used to synchronize access to {@link #channelAllocator}.
     */
    private final Object channelAllocatorSyncRoot = new Object();

    /**
     * The {@link OctoChannelAllocator}, if any, which is currently
     * allocating channels for this participant.
     */
    private OctoChannelAllocator channelAllocator = null;

    /**
     * A flag which determines when the session can be considered established.
     * This is initially set to false, and raised when the colibri channels
     * are allocated (at which point we know they IDs and we can send updates
     * via colibri).
     */
    private boolean sessionEstablished = false;

    /**
     * The list of remote Octo relay IDs for this {@link OctoParticipant},
     * i.e. the relays which will be set for the Octo channels.
     *
     * This should be the list of the relay IDs of all bridges in the conference,
     * with the current bridge's relay ID removed.
     */
    private List<String> relays;

    /**
     * The sources associated with this octo participant, i.e. the sources of all endpoints on different bridges.
     */
    private final ConferenceSourceMap sources = new ConferenceSourceMap();

    private final Logger logger;

    /**
     * Initializes a new {@link OctoParticipant} instance.
     * @param relays the list of Octo relays
     * @param bridgeJid the JID of the bridge that this participant
     */
    OctoParticipant(List<String> relays, Logger parentLogger, Jid bridgeJid)
    {
        logger = parentLogger.createChildLogger(getClass().getName());
        logger.addContext("bridge", bridgeJid.getResourceOrEmpty().toString());
        this.relays = relays;
    }

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
     * Replaces the {@link OctoChannelAllocator}, which is currently
     * allocating channels for this participant (if any) with the specified
     * channel allocator (if any).
     * @param channelAllocator the channel allocator to set, or {@code null}
     * to clear it.
     */
    public void setChannelAllocator(OctoChannelAllocator channelAllocator)
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
     * {@link OctoChannelAllocator} has completed its task and its thread
     * is about to terminate.
     * @param channelAllocator the {@link OctoChannelAllocator} which has
     * completed its task and its thread is about to terminate.
     */
    public void channelAllocatorCompleted(OctoChannelAllocator channelAllocator)
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
     * Removes a set of sources from this participant.
     */
    public void removeSources(ConferenceSourceMap sourcesToRemove)
    {
        logger.debug(() -> "Removing sources: " + sourcesToRemove);
        sources.remove(sourcesToRemove);
        logger.debug(() -> "Remaining sources: " + sources);
    }

    public void addSources(ConferenceSourceMap sourcesToAdd)
    {
        logger.debug(() -> "Adding sources: " + sourcesToAdd);
        this.sources.add(sourcesToAdd);
        logger.debug(() -> "Resulting sources: " + sources);
    }



    /**
     * Sets the list of Octo relay IDs for this {@link OctoParticipant}.
     * @param relays the relays to set.
     */
    void setRelays(List<String> relays)
    {
        this.relays = relays;
    }

    /**
     * @return the list of Octo relay IDs for this {@link OctoParticipant}>
     */
    List<String> getRelays()
    {
        return relays;
    }

    public ConferenceSourceMap getSources()
    {
        return sources.unmodifiable();
    }

    /**
     * {@inheritDoc}
     */
    synchronized public boolean isSessionEstablished()
    {
        return sessionEstablished;
    }

    /**
     * Sets the "session established" flag.
     * @param sessionEstablished the value to set.
     */
    synchronized void setSessionEstablished(boolean sessionEstablished)
    {
        this.sessionEstablished = sessionEstablished;
    }

    /**
     * Updates the sources and source groups of this participant with the
     * sources and source groups scheduled to be added or removed via
     * {@link #queueRemoteSourcesToAdd(ConferenceSourceMap)} and
     * {@link #queueRemoteSourcesToRemove(ConferenceSourceMap)}.
     *
     * @return {@code true} if the call resulted in this participant's sources to change, and {@code false} otherwise.
     */
    synchronized boolean updateSources()
    {
        boolean changed = false;

        for (SourcesToAddOrRemove sourcesToAddOrRemove : clearQueuedRemoteSourceChanges())
        {
            changed = true;

            AddOrRemove action = sourcesToAddOrRemove.getAction();
            ConferenceSourceMap sources = sourcesToAddOrRemove.getSources();

            if (action == AddOrRemove.Add)
            {
                addSources(sources);
            }
            else if (action == AddOrRemove.Remove)
            {
                removeSources(sources);
            }
        }

        return changed;
    }

    @Override
    public String toString()
    {
        return "OctoParticipant[relays=" + relays + "]@" + hashCode();
    }
}
