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
package org.jitsi.protocol.xmpp.util;

import org.jitsi.xmpp.extensions.colibri.*;
import org.jitsi.xmpp.extensions.jingle.*;

import org.jitsi.utils.*;

import java.util.*;
import java.util.stream.*;

/**
 * Class maps lists of source groups to media types and encapsulates various
 * utility operations.
 *
 * @author Pawel Domas
 */
public class MediaSourceGroupMap
{
    /**
     * Map backend.
     */
    private final Map<String, List<SourceGroup>> groupMap;

    /**
     * Creates new instance of <tt>MediaSourceGroupMap</tt>.
     */
    public MediaSourceGroupMap()
    {
        this(new HashMap<>());
    }

    /**
     * Creates new instance of <tt>MediaSourceGroupMap</tt>.
     * @param map the map with predefined values that will be used by new
     *            instance.
     */
    private MediaSourceGroupMap(Map<String, List<SourceGroup>> map)
    {
        this.groupMap = map;
    }

    /**
     * Finds and extracts video {@link SimulcastGrouping}s.
     *
     * @return a {@link List} of {@link SimulcastGrouping}s.
     */
    public List<SimulcastGrouping> findSimulcastGroupings()
    {
        List<SourceGroup> simGroups = getSimulcastGroups();
        List<SimulcastGrouping> simulcastGroupings = new LinkedList<>();

        for (SourceGroup simGroup : simGroups)
        {
            if (!simGroup.getSemantics().equals(
                    SourceGroupPacketExtension.SEMANTICS_SIMULCAST))
            {
                continue;
            }

            // The simulcast grouping will consist of the main SIM group and
            // eventual FID subgroups
            List<SourceGroup> fidGroups = getRtxGroups();
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

            SimulcastGrouping simGrouping
                = new SimulcastGrouping(simGroup, relatedFidGroups);

            simulcastGroupings.add(simGrouping);
        }

        return simulcastGroupings;
    }

    /**
     * Gets video RTX (FID) groups.
     *
     * @return a {@link List} of {@link SourceGroup}.
     */
    public List<SourceGroup> getRtxGroups()
    {
        return findSourceGroups(
                MediaType.VIDEO.toString(),
                SourceGroupPacketExtension.SEMANTICS_FID);
    }

    /**
     * Finds video Simulcast groups.
     *
     * @return a {@link List} of {@link SourceGroup}.
     */
    public List<SourceGroup> getSimulcastGroups()
    {
        return findSourceGroups(
                MediaType.VIDEO.toString(),
                SourceGroupPacketExtension.SEMANTICS_SIMULCAST);
    }

    /**
     * Returns the list of {@link SourceGroup} for given media type.
     * @param mediaType the name of media type for which list of source groups
     * will be returned.
     */
    public List<SourceGroup> getSourceGroupsForMedia(String mediaType)
    {
        return groupMap.computeIfAbsent(mediaType, k -> new ArrayList<>());
    }

    /**
     * Finds groups that match given media type and semantics.
     *
     * @param mediaType eg. 'audio', 'video', etc.
     * @param semantics a group semantics eg. 'SIM' or 'FID'
     *
     * @return a {@link List} of {@link SourceGroup}
     */
    private List<SourceGroup> findSourceGroups(
        String mediaType, String semantics)
    {
        Objects.requireNonNull(semantics, "semantics");

        List<SourceGroup> mediaGroups = groupMap.get(mediaType);

        return mediaGroups.stream()
            .filter(mg -> semantics.equalsIgnoreCase(mg.getSemantics()))
            .collect(Collectors.toList());
    }

    /**
     * Extracts source groups from Jingle content list.
     * @param contents the list of <tt>ContentPacketExtension</tt> which will be
     *                 examined for media source groups.
     * @return <tt>MediaSourceGroupMap</tt> that reflects source groups of media
     *         described by given content list.
     */
    public static MediaSourceGroupMap getSourceGroupsForContents(
            List<ContentPacketExtension> contents)
    {
        MediaSourceGroupMap mediaSourceGroupMap = new MediaSourceGroupMap();

        for (ContentPacketExtension content : contents)
        {
            List<SourceGroup> mediaGroups
                = mediaSourceGroupMap.getSourceGroupsForMedia(content.getName());

            // FIXME: does not check for duplicates
            mediaGroups.addAll(SourceGroup.getSourceGroupsForContent(content));
        }

        return mediaSourceGroupMap;
    }

    /**
     * Returns all media types stored in this map(some of them might be empty).
     */
    public List<String> getMediaTypes()
    {
        return new ArrayList<>(groupMap.keySet());
    }

    /**
     * Adds mapping of source group to media type.
     * @param mediaType the media type name.
     * @param sourceGroup <tt>SourceGroup</tt> that will be mapped to given media
     *                  type.
     */
    public void addSourceGroup(String mediaType, SourceGroup sourceGroup)
    {
        getSourceGroupsForMedia(mediaType).add(sourceGroup);
    }

    /**
     * Adds source groups contained in given <tt>MediaSourceGroupMap</tt> to this
     * map instance.
     * @param sourceGroups the <tt>MediaSourceGroupMap</tt> that will be added to
     *                   this map instance.
     */
    public void add(MediaSourceGroupMap sourceGroups)
    {
        sourceGroups.getMediaTypes().forEach(
            mediaType -> sourceGroups.getSourceGroupsForMedia(mediaType)
                .forEach(
                    group -> addSourceGroup(mediaType, group)));
    }

    /**
     * Checks whether or not this map contains a given {@link SourceGroup}.
     * @param mediaType the type of the media for the group to be found.
     * @param toFind the <tt>SourceGroup</tt> to be found.
     * @return <tt>true</tt> if the given <tt>SourceGroup</tt> exists in the map
     * already or <tt>false</tt> otherwise. A group is considered equal when it
     * has the same semantics and sources stored. The order of sources appearing
     * in the group is important as well.
     */
    public boolean containsGroup(String mediaType, SourceGroup toFind)
    {
        return getSourceGroupsForMedia(mediaType).stream()
            .anyMatch(group -> group.equals(toFind));
    }

    /**
     * Returns <tt>true</tt> if this map contains any non-empty source groups.
     */
    public boolean isEmpty()
    {
        for (String mediaType : groupMap.keySet())
        {
            if (!getSourceGroupsForMedia(mediaType).isEmpty())
            {
                return false;
            }
        }
        return true;
    }

    /**
     * Removes source groups contained in the given <tt>MediaSourceGroupMap</tt> from
     * this map if they exist.
     * @param mapToRemove the <tt>MediaSourceGroupMap</tt> that contains source
     *                    groups mappings to be removed from this instance.
     * @return the <tt>MediaSourceGroupMap</tt> that contains only these source
     *         groups which were actually removed(existed in this map).
     */
    public MediaSourceGroupMap remove(MediaSourceGroupMap mapToRemove)
    {
        MediaSourceGroupMap removedGroups = new MediaSourceGroupMap();

        for (String mediaType : mapToRemove.groupMap.keySet())
        {
            List<SourceGroup> groupList = getSourceGroupsForMedia(mediaType);
            List<SourceGroup> toBeRemoved= new ArrayList<>();

            for (SourceGroup sourceGroupToCheck
                : mapToRemove.groupMap.get(mediaType))
            {
                for (SourceGroup sourceGroup : groupList)
                {
                    if (sourceGroupToCheck.equals(sourceGroup))
                    {
                        toBeRemoved.add(sourceGroup);
                    }
                }
            }

            removedGroups.getSourceGroupsForMedia(mediaType).addAll(toBeRemoved);

            groupList.removeAll(toBeRemoved);
        }

        return removedGroups;
    }

    /**
     * Returns deep copy of this map instance.
     */
    public MediaSourceGroupMap copy()
    {
        Map<String, List<SourceGroup>> mapCopy = new HashMap<>();

        for (String mediaType : groupMap.keySet())
        {
            List<SourceGroup> listToCopy
                = new ArrayList<>(groupMap.get(mediaType));
            List<SourceGroup> listCopy = new ArrayList<>(listToCopy.size());

            for (SourceGroup group : listToCopy)
            {
                listCopy.add(group.copy());
            }

            mapCopy.put(mediaType, listCopy);
        }

        return new MediaSourceGroupMap(mapCopy);
    }

    private String groupsToString(List<SourceGroup> sources)
    {
        StringBuilder str = new StringBuilder();
        for (SourceGroup group : sources)
        {
            SourceGroupPacketExtension sourceGroup = group.getExtensionCopy();

            str.append("SourceGroup(")
                .append(sourceGroup.getSemantics())
                .append(")[ ");

            for (SourcePacketExtension source : sourceGroup.getSources())
            {
                str.append(source.toString()).append(" ");
            }
            str.append("]");
        }
        return str.toString();
    }

    /**
     * Converts to a map of <tt>SourceGroupPacketExtension</tt>.
     * @return a map of Colibri content's names to the lists of
     *         <tt>SourceGroupPacketExtension</tt> which reflects current state
     *         of this <tt>MediaSourceGroupMap</tt>.
     */
    public Map<String, List<SourceGroupPacketExtension>> toMap()
    {
        Map<String, List<SourceGroupPacketExtension>> map = new HashMap<>();

        for (String mediaType : groupMap.keySet())
        {
            List<SourceGroup> groups = groupMap.get(mediaType);
            List<SourceGroupPacketExtension> peGroups
                = new ArrayList<>(groups.size());

            for (SourceGroup group : groups)
            {
                peGroups.add(group.getExtensionCopy());
            }

            map.put(mediaType, peGroups);
        }

        return map;
    }

    @Override
    public String toString()
    {
        StringBuilder str = new StringBuilder("source_Groups{");
        for (String mediaType : getMediaTypes())
        {
            str.append(" ").append(mediaType).append(":[ ");
            str.append(groupsToString(getSourceGroupsForMedia(mediaType)));
            str.append(" ]");
        }
        return str.append(" }@").append(hashCode()).toString();
    }
}
