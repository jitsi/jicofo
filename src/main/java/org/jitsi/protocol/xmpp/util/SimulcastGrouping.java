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

import org.jitsi.xmpp.extensions.colibri.*;
import org.jitsi.xmpp.extensions.jingle.*;

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
        if (!fidGroups.isEmpty())
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
        return
            SourceRidGroupPacketExtension.ELEMENT_NAME.equals(
                    simGroup.getPacketExtension().getElementName());
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
