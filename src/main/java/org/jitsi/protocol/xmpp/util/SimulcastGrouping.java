/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ Atlassian Pty Ltd
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
package org.jitsi.protocol.xmpp.util;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;

import java.util.*;

/**
 * Utility class for the simulcast {@link SourcePacketExtension} grouping.
 */
public class SimulcastGrouping
{
    /**
     * The list of FID(RTX) subgroups.
     */
    private final List<SourceGroup> fidGroups;

    /**
     * The main SIM group.
     */
    private final SourceGroup simGroup;

    /**
     * Extracts new {@link SimulcastGrouping} from given
     * {@link MediaSourceGroupMap} for given root simulcast group.
     *
     * @param mediaType the type of the media of the SIM group.
     * @param groupsMap the map of all groups of all media types.
     * @param simGroup the root SIM group for which {@link SimulcastGrouping}
     *        will be extracted.
     *
     * @return {@link SimulcastGrouping} which contains SIM and all FID
     * subgroups.
     */
    public static SimulcastGrouping extractSimGrouping(
            String                 mediaType,
            MediaSourceGroupMap    groupsMap,
            SourceGroup            simGroup)
    {
        if (!simGroup.getSemantics().equals(
                SourceGroupPacketExtension.SEMANTICS_SIMULCAST))
        {
            throw new IllegalArgumentException("Not a SIM group: " + simGroup);
        }

        // The simulcast grouping will consist of the main SIM group and
        // eventual FID subgroups
        List<SourceGroup> fidGroups = groupsMap.getRtxGroups(mediaType);
        List<SourceGroup> relatedFidGroups = new LinkedList<>();

        for (SourcePacketExtension source : simGroup.getSources())
        {
            for (SourceGroup group : fidGroups)
            {
                if (group.belongsToGroup(source))
                {
                    relatedFidGroups.add(group);
                }
            }
        }

        return new SimulcastGrouping(simGroup, relatedFidGroups);
    }

    /**
     * Creates new {@link SimulcastGrouping} for given SIM and FID groups.
     * @param simGroup a SIM {@link SourceGroup}.
     * @param fidGroups a {@link List} of FID subgroups.
     */
    public SimulcastGrouping(SourceGroup simGroup, List<SourceGroup> fidGroups)
    {
        Objects.requireNonNull(simGroup, "simGroup");

        this.simGroup = simGroup;
        this.fidGroups
            = fidGroups != null ? fidGroups : new ArrayList<SourceGroup>(0);

        int simGroupSize = simGroup.getSources().size();
        int fidGroupCount = this.fidGroups.size();

        // Each source in SIM group should contain one corresponding FID group
        if (fidGroupCount != 0 && fidGroupCount != simGroupSize)
        {
            throw new IllegalArgumentException(
                    "SIM group size != FID group count: "
                        + simGroup +" != " + fidGroupCount);
        }
    }

    /**
     * Checks if any of the {@link SourcePacketExtension}s which belong to
     * the given group is contained in this {@link SimulcastGrouping}.
     *
     * @param group {@link SourceGroup} to be checked.
     *
     * @return <tt>true</tt> or <tt>false</tt>.
     */
    public boolean belongsToSimulcastGrouping(SourceGroup group)
    {
        for (SourcePacketExtension src : group.getSources())
        {
            if (belongsToSimulcastGrouping(src))
            {
                return true;
            }
        }

        return false;
    }

    /**
     * Checks if given {@link SourcePacketExtension} belongs to this
     * {@link SimulcastGrouping}.
     *
     * @param src {@link SourcePacketExtension} to be checked.
     *
     * @return <tt>true</tt> or <tt>false</tt>.
     */
    public boolean belongsToSimulcastGrouping(SourcePacketExtension src)
    {
        if (fidGroups.size() > 0)
        {
            for (SourceGroup fidGroup : fidGroups)
            {
                if (fidGroup.belongsToGroup(src))
                {
                    return true;
                }
            }

            // If there are FID groups they should contain all SIM sources
            return false;
        }

        return simGroup.belongsToGroup(src);
    }

    /**
     * Gets the MSID for this {@link SimulcastGrouping}.
     *
     * @return {@link String}
     */
    public String getSimulcastMsid()
    {
        return simGroup.getGroupMsid();
    }

    /**
     * Checks if this SIM group is using RID signaling.
     *
     * @return <tt>true</tt> or <tt>false</tt>
     */
    public boolean isUsingRidSignaling()
    {
        List<SourcePacketExtension> sources = simGroup.getSources();

        return sources.size() > 0 && sources.get(0).hasRid();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString()
    {
        StringBuilder str = new StringBuilder("Simulcast[");

        for (SourcePacketExtension simSource : simGroup.getSources())
        {
            str.append(simSource.toString()).append(",");
        }

        for (SourceGroup fidGroup : fidGroups)
        {
            str.append(fidGroup);
        }

        str.append("]");

        return str + "@" + hashCode();
    }
}
