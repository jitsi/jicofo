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

import org.jitsi.jicofo.conference.source.*;
import org.jitsi.utils.logging2.*;
import org.jxmpp.jid.*;

import java.util.*;

/**
 * Implements a {@link AbstractParticipant} for Octo. Manages the colibri
 * channels used for Octo on a particular jitsi-videobridge instance, and
 * the sources and source groups which need to be added to these colibri
 * channels (i.e. all sources and source groups from real participants in the
 * conference on other bridges).
 *
 * @author Boris Grozev
 */
public class OctoParticipant
    extends AbstractParticipant
{
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
        super(parentLogger);
        logger = parentLogger.createChildLogger(getClass().getName());
        logger.addContext("bridge", bridgeJid.getResourceOrEmpty().toString());
        this.relays = relays;
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

    @Override
    public ConferenceSourceMap getSources()
    {
        return sources.unmodifiable();
    }

    /**
     * {@inheritDoc}
     */
    @Override
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
