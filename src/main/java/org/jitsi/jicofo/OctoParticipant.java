/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2015-2018 Atlassian Pty Ltd
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

import org.jitsi.protocol.xmpp.util.*;

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
     * Initializes a new {@link OctoParticipant} instance.
     * @param conference the {@link JitsiMeetConference} which this participant
     * will be a part of.
     * @param relays the list of Octo relays
     */
    OctoParticipant(JitsiMeetConference conference, List<String> relays)
    {
        super(conference.getLogger());
        this.relays = relays;
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
     * {@link #scheduleSourcesToAdd(MediaSourceMap)},
     * {@link #scheduleSourceGroupsToAdd(MediaSourceGroupMap)},
     * {@link #scheduleSourcesToRemove(MediaSourceMap)},
     * {@link #scheduleSourceGroupsToRemove(MediaSourceGroupMap)}
     *
     * @return {@code true} if the call resulted in this participant's sources
     * or source groups to change, and {@code false} otherwise.
     */
    synchronized boolean updateSources()
    {
        boolean changed = false;

        MediaSourceMap sourcesToAdd = getSourcesToAdd();
        MediaSourceGroupMap sourceGroupsToAdd = getSourceGroupsToAdd();
        MediaSourceMap sourcesToRemove = getSourcesToRemove();
        MediaSourceGroupMap sourceGroupsToRemove = getSourceGroupsToRemove();

        clearSourcesToAdd();
        clearSourcesToRemove();

        // We don't have any information about the order in which the add/remove
        // operations were requested. If an SSRC is present in both
        // sourcesToAdd and sourcesToRemove we choose to include it. That is,
        // we err on the side of signaling more sources than necessary.
        sourcesToRemove.remove(sourcesToAdd);
        sourceGroupsToRemove.remove(sourceGroupsToAdd);

        if (!sourcesToAdd.isEmpty() || !sourceGroupsToAdd.isEmpty())
        {
            addSourcesAndGroups(sourcesToAdd, sourceGroupsToAdd);
            changed = true;
        }

        if (!sourcesToRemove.isEmpty() || !sourceGroupsToRemove.isEmpty())
        {
            removeSources(sourcesToRemove);
            removeSourceGroups(sourceGroupsToRemove);
            changed = true;
        }

        return changed;
    }

    @Override
    public String toString()
    {
        return "OctoParticipant[relays=" + relays + "]@" + hashCode();
    }
}
