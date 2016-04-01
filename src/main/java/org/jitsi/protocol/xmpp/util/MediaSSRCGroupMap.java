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

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;

import java.util.*;

/**
 * Class maps lists of SSRC groups to media types and encapsulates various
 * utility operations.
 *
 * @author Pawel Domas
 */
public class MediaSSRCGroupMap
{
    /**
     * Map backend.
     */
    private final Map<String, List<SSRCGroup>> groupMap;

    /**
     * Creates new instance of <tt>MediaSSRCGroupMap</tt>.
     */
    public MediaSSRCGroupMap()
    {
        this(new HashMap<String, List<SSRCGroup>>());
    }

    /**
     * Creates new instance of <tt>MediaSSRCGroupMap</tt>.
     * @param map the map with predefined values that will be used by new
     *            instance.
     */
    private MediaSSRCGroupMap(Map<String, List<SSRCGroup>> map)
    {
        this.groupMap = map;
    }

    /**
     * Returns the list of {@link SSRCGroup} for given media type.
     * @param media the name of media type for which list of SSRC groups will be
     *              returned.
     */
    public List<SSRCGroup> getSSRCGroupsForMedia(String media)
    {
        List<SSRCGroup> mediaGroups = groupMap.get(media);
        if (mediaGroups == null)
        {
            mediaGroups = new ArrayList<>();
            groupMap.put(media, mediaGroups);
        }
        return mediaGroups;
    }

    /**
     * Extracts SSRC groups from Jingle content list.
     * @param contents the list of <tt>ContentPacketExtension</tt> which will be
     *                 examined for media SSRC groups.
     * @return <tt>MediaSSRCGroupMap</tt> that reflects SSRC groups of media
     *         described by given content list.
     */
    public static MediaSSRCGroupMap getSSRCGroupsForContents(
            List<ContentPacketExtension> contents)
    {
        MediaSSRCGroupMap mediaSSRCGroupMap = new MediaSSRCGroupMap();

        for (ContentPacketExtension content : contents)
        {
            List<SSRCGroup> mediaGroups
                = mediaSSRCGroupMap.getSSRCGroupsForMedia(content.getName());

            // FIXME: does not check for duplicates
            mediaGroups.addAll(SSRCGroup.getSSRCGroupsForContent(content));
        }

        return mediaSSRCGroupMap;
    }

    /**
     * Returns all media types stored in this map(some of them might be empty).
     */
    public List<String> getMediaTypes()
    {
        return new ArrayList<>(groupMap.keySet());
    }

    /**
     * Adds mapping of SSRC group to media type.
     * @param media the media type name.
     * @param ssrcGroup <tt>SSRCGroup</tt> that will be mapped to given media
     *                  type.
     */
    public void addSSRCGroup(String media, SSRCGroup ssrcGroup)
    {
        getSSRCGroupsForMedia(media).add(ssrcGroup);
    }

    /**
     * Adds mapping of SSRC groups to media type.
     * @param media the media type name.
     * @param ssrcGroups <tt>SSRCGroup</tt>s that will be mapped to given media
     *                  type.
     */
    public void addSSRCGroups(String media, List<SSRCGroup> ssrcGroups)
    {
        getSSRCGroupsForMedia(media).addAll(ssrcGroups);
    }

    /**
     * Adds SSRC groups contained in given <tt>MediaSSRCGroupMap</tt> to this
     * map instance.
     * @param ssrcGroups the <tt>MediaSSRCGroupMap</tt> that will be added to
     *                   this map instance.
     * @return returns that map that contains only those groups that were
     *         actually added.
     */
    public MediaSSRCGroupMap add(MediaSSRCGroupMap ssrcGroups)
    {
        MediaSSRCGroupMap addedGroups = new MediaSSRCGroupMap();
        for (String media : ssrcGroups.getMediaTypes())
        {
            List<SSRCGroup> groups = ssrcGroups.getSSRCGroupsForMedia(media);
            for (SSRCGroup group : groups)
            {
                if (!group.isEmpty())
                {
                    addSSRCGroup(media, group);

                    addedGroups.addSSRCGroup(media, group);
                }
            }
        }
        return addedGroups;
    }

    /**
     * Returns <tt>true</tt> if this map contains any SSRC groups.
     */
    public boolean isEmpty()
    {
        for (String media : groupMap.keySet())
        {
            if (!getSSRCGroupsForMedia(media).isEmpty())
            {
                return false;
            }
        }
        return true;
    }

    /**
     * Removes SSRC groups contained ing given <tt>MediaSSRCGroupMap</tt> from
     * this map if they exist.
     * @param mapToRemove the <tt>MediaSSRCGroupMap</tt> that contains SSRC
     *                    groups mappings to be removed from this instance.
     * @return the <tt>MediaSSRCGroupMap</tt> that contains only these SSRC
     *         groups which were actually removed(existed in this map).
     */
    public MediaSSRCGroupMap remove(MediaSSRCGroupMap mapToRemove)
    {
        MediaSSRCGroupMap removedGroups = new MediaSSRCGroupMap();

        for (String media : mapToRemove.groupMap.keySet())
        {
            List<SSRCGroup> groupList = getSSRCGroupsForMedia(media);
            List<SSRCGroup> toBeRemoved= new ArrayList<>();

            for (SSRCGroup ssrcGroupToCheck
                : mapToRemove.groupMap.get(media))
            {
                for (SSRCGroup ssrcGroup : groupList)
                {
                    if (ssrcGroupToCheck.equals(ssrcGroup))
                    {
                        toBeRemoved.add(ssrcGroup);
                    }
                }
            }

            removedGroups.getSSRCGroupsForMedia(media).addAll(toBeRemoved);

            groupList.removeAll(toBeRemoved);
        }

        return removedGroups;
    }

    /**
     * Returns deep copy of this map instance.
     */
    public MediaSSRCGroupMap copy()
    {
        Map<String, List<SSRCGroup>> mapCopy = new HashMap<>();

        for (String media : groupMap.keySet())
        {
            List<SSRCGroup> listToCopy = new ArrayList<>(groupMap.get(media));
            List<SSRCGroup> listCopy = new ArrayList<>(listToCopy.size());

            for (SSRCGroup group : listToCopy)
            {
                listCopy.add(group.copy());
            }

            mapCopy.put(media, listCopy);
        }

        return new MediaSSRCGroupMap(mapCopy);
    }

    String groupsToString(List<SSRCGroup> ssrcs)
    {
        StringBuilder str = new StringBuilder();
        for (SSRCGroup group : ssrcs)
        {
            SourceGroupPacketExtension sourceGroup = group.getExtensionCopy();

            str.append("SSRCGroup(")
                .append(sourceGroup.getSemantics())
                .append(")[ ");

            for (SourcePacketExtension ssrc : sourceGroup.getSources())
            {
                str.append(ssrc.getSSRC()).append(" ");
            }
            str.append("]");
        }
        return str.toString();
    }

    /**
     * Converts to a map of <tt>SourceGroupPacketExtension</tt>.
     * @return a map of Colibri content's names to the lists of
     *         <tt>SourceGroupPacketExtension</tt> which reflects current state
     *         of this <tt>MediaSSRCGroupMap</tt>.
     */
    public Map<String, List<SourceGroupPacketExtension>> toMap()
    {
        Map<String, List<SourceGroupPacketExtension>> map = new HashMap<>();

        for (String media : groupMap.keySet())
        {
            List<SSRCGroup> groups = groupMap.get(media);
            List<SourceGroupPacketExtension> peGroups
                = new ArrayList<>(groups.size());

            for (SSRCGroup group : groups)
            {
                peGroups.add(group.getExtensionCopy());
            }

            map.put(media, peGroups);
        }

        return map;
    }

    @Override
    public String toString()
    {
        StringBuilder str = new StringBuilder("SSRC_Groups{");
        for (String media : getMediaTypes())
        {
            str.append(" ").append(media).append(":[ ");
            str.append(groupsToString(getSSRCGroupsForMedia(media)));
            str.append(" ]");
        }
        return str.append(" }@").append(hashCode()).toString();
    }
}
