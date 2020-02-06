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
import org.jxmpp.jid.*;
import org.jitsi.utils.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

/**
 * The map of media <tt>SourcePacketExtension</tt> encapsulates various
 * manipulation and access operations.
 *
 * @author Pawel Domas
 */
public class MediaSourceMap
{
    /**
     * The media source map storage.
     *
     * CopyOnWriteArrayList "never changes during the lifetime of the
     * iterator, so interference is impossible and the iterator is
     * guaranteed not to throw ConcurrentModificationException" so using it
     * makes sure we don't hit any ConcurrentModificationExceptions if we
     * iterate to make modifications
     */
    private final
        Map<String, CopyOnWriteArrayList<SourcePacketExtension>> sources;

    /**
     * Creates new empty instance of <tt>MediaSourceMap</tt>.
     */
    public MediaSourceMap()
    {
        this(new HashMap<>());
    }

    /**
     * Creates new instance of <tt>MediaSourceMap</tt> initialized with given map
     * of media sources.
     *
     * @param sources initial map of media sources.
     */
    private MediaSourceMap(
        Map<String, CopyOnWriteArrayList<SourcePacketExtension>> sources)
    {
        this.sources = sources;
    }

    /**
     * Returns the list of <tt>SourcePacketExtension</tt> for given media type
     * contained in this map.
     *
     * @param mediaType the media type for which the list of
     *              <tt>SourcePacketExtension</tt> will be returned.
     */
    public List<SourcePacketExtension> getSourcesForMedia(String mediaType)
    {
        return
            sources.computeIfAbsent(
                mediaType, k -> new CopyOnWriteArrayList<>());
    }

    /**
     * Returns all media types contained in this map.
     */
    public Set<String> getMediaTypes()
    {
        return sources.keySet();
    }

    /**
     * Merges sources from given map with this instance.
     *
     * @param mapToMerge the map of media sources to be included in this map.
     */
    public void add(MediaSourceMap mapToMerge)
    {
        mapToMerge.sources.forEach(this::addSources);
    }

    /**
     * Adds source to this map. NOTE that duplicated sources wil be stored in
     * the map.
     *
     * @param mediaType the media type of the source to be added.
     *
     * @param source the <tt>SourcePacketExtension</tt> to be added to this map.
     */
    public void addSource(String mediaType, SourcePacketExtension source)
    {
        // BEWARE! add will not detect duplications
        getSourcesForMedia(mediaType).add(source);
    }

    /**
     * Adds sources to this map. NOTE that duplicates will NOT be stored in the map.
     *
     * @param mediaType the media type of sources to be added to this map.
     *
     * @param newSources collection of sources which will be included in this map.
     */
    public void addSources(
        String mediaType, Collection<SourcePacketExtension> newSources)
    {
        // Remove duplicates before adding
        List<SourcePacketExtension> mediaSources = getSourcesForMedia(mediaType);

        mediaSources.removeIf(s -> newSources.stream().anyMatch(s2 -> s.getSSRC() == s2.getSSRC()));

        mediaSources.addAll(newSources);
    }

    /**
     * Creates a deep copy of this <tt>MediaSourceMap</tt>.
     *
     * @return a new instance of <tt>MediaSourceMap</tt> which contains copies of
     *         <tt>SourcePacketExtension</tt> stored in this map.
     */
    public MediaSourceMap copyDeep()
    {
        Map<String, CopyOnWriteArrayList<SourcePacketExtension>> mapCopy
            = new HashMap<>();

        for (String mediaType : sources.keySet())
        {
            CopyOnWriteArrayList<SourcePacketExtension> sourcesCopy
                = sources.get(mediaType).stream()
                    .map(SourcePacketExtension::copy)
                    .collect(
                        Collectors.toCollection(CopyOnWriteArrayList::new));

            mapCopy.put(mediaType, sourcesCopy);
        }

        return new MediaSourceMap(mapCopy);
    }

    /**
     * Looks for SSRC in this map.
     *
     * @param mediaType the media type of the SSRC we're looking for.
     *
     * @param sourceToFind the <tt>SourcePacketExtension</tt> we're looking for.
     *
     * @return <tt>SourcePacketExtension</tt> found in this map which has
     *         the same SSRC number as given in the <tt>ssrcValue</tt> or
     *         <tt>null</tt> if not found.
     */
    public SourcePacketExtension findSource(
        String mediaType, SourcePacketExtension sourceToFind)
    {
        return getSourcesForMedia(mediaType).stream()
            .filter(source -> source.sourceEquals(sourceToFind))
            .findFirst()
            .orElse(null);
    }

    public SourcePacketExtension findSourceViaSsrc(String mediaType, long ssrc)
    {
        return getSourcesForMedia(mediaType).stream()
            .filter(source -> source.hasSSRC() && source.getSSRC() == ssrc)
            .findFirst()
            .orElse(null);
    }

    /**
     * Finds {@link SourcePacketExtension} that have the given MSID.
     *
     * @param mediaType the type of the media to be searched.
     * @param groupMsid the MSID to be found.
     *
     * @return a {@link List} of {@link SourcePacketExtension} that matches
     * the given MSID.
     */
    public List<SourcePacketExtension> findSourcesWithMsid(
            String mediaType, String groupMsid)
    {
        if (StringUtils.isNullOrEmpty(groupMsid))
        {
            throw new IllegalArgumentException("Null or empty 'groupMsid'");
        }

        List<SourcePacketExtension> mediaSources
            = getSourcesForMedia(mediaType);

        return mediaSources.stream()
            .filter(
                source -> groupMsid.equalsIgnoreCase(
                    SSRCSignaling.getMsid(source)))
            .collect(Collectors.toCollection(LinkedList::new));
    }

    /**
     * Looks for given source and returns type of the media for the first
     * match found.
     * @param source the source to be found
     * @return type of the media of the SSRC identified by the given number or
     * <tt>null</tt> if not found.
     */
    public String getMediaTypeForSource(SourcePacketExtension source)
    {
        Set<String> mediaTypes = getMediaTypes();
        return mediaTypes.stream()
            .filter(mediaType -> findSource(mediaType, source) != null)
            .findFirst()
            .orElse(null);
    }

    /**
     * Finds SSRC for given owner.
     *
     * @param mediaType the media type of the SSRC we'll be looking for.
     * @param owner the MUC JID of the SSRC owner.
     *
     * @return <tt>SourcePacketExtension</tt> for given media type and owner or
     *  <tt>null</tt> if not found.
     */
    public SourcePacketExtension findSsrcForOwner(String mediaType, Jid owner)
    {
        List<SourcePacketExtension> mediaSSRCs = getSourcesForMedia(mediaType);
        return mediaSSRCs.stream()
            .filter(
                ssrc ->
                {
                    Jid ssrcOwner = SSRCSignaling.getSSRCOwner(ssrc);
                    return ssrcOwner != null && ssrcOwner.equals(owner);
                })
            .findFirst()
            .orElse(null);
    }

    /**
     * Removes sources contained in given map from this instance.
     *
     * @param mapToRemove the map that contains media sources to be removed from
     *                    this instance f they are present.
     * @return the <tt>MediaSourceMap</tt> that contains only these sources that
     *         were actually removed(existed in this map).
     */
    public MediaSourceMap remove(MediaSourceMap mapToRemove)
    {
        MediaSourceMap removedSources = new MediaSourceMap();
        // FIXME: fix duplication
        for (String mediaType : mapToRemove.sources.keySet())
        {
            List<SourcePacketExtension> sourceList = getSourcesForMedia(mediaType);
            List<SourcePacketExtension> toBeRemoved = new ArrayList<>();

            for (SourcePacketExtension sourceToRemove
                    : mapToRemove.sources.get(mediaType))
            {
                sourceList.stream()
                    .filter(sourceToRemove::sourceEquals)
                    .forEach(toBeRemoved::add);
            }

            sourceList.removeAll(toBeRemoved);
            removedSources.getSourcesForMedia(mediaType).addAll(toBeRemoved);
        }
        return removedSources;
    }

    /**
     * Removes given source from this map.
     *
     * @param mediaType the media type of the source to be removed.
     * @param source the <tt>SourcePacketExtension</tt> to be removed from this
     *        <tt>MediaSourceMap</tt>.
     *
     * @return <tt>true</tt> if the source has been actually removed which means
     *         that it was in the map before the operation took place.
     */
    public boolean remove(String mediaType, SourcePacketExtension source)
    {
        SourcePacketExtension toBeRemoved = findSource(mediaType, source);
        return toBeRemoved != null && getSourcesForMedia(mediaType).remove(source);
    }

    /**
     * Returns <tt>true</tt> if this map does not contain any
     * <tt>SourcePacketExtension</tt>s or <tt>false</tt> otherwise.
     */
    public boolean isEmpty()
    {
        return sources.keySet().stream()
            .allMatch(mediaType -> getSourcesForMedia(mediaType).isEmpty());
    }

    /**
     * Extracts <tt>SourcePacketExtension</tt> from given <tt>contents</tt> list
     * and creates {@link MediaSourceMap} that reflects this contents list.
     *
     * @param contents the list of {@link ContentPacketExtension} from which
     *                 <tt>SourcePacketExtension</tt> will be extracted and put
     *                 into {@link MediaSourceMap}.
     *
     * @return the {@link MediaSourceMap} that describes given <tt>contents</tt>
     *         list.
     */
    public static MediaSourceMap getSourcesFromContent(
        List<ContentPacketExtension> contents)
    {
        Map<String, CopyOnWriteArrayList<SourcePacketExtension>>
            mediaSourceMap = new HashMap<>();

        for (ContentPacketExtension content : contents)
        {
            RtpDescriptionPacketExtension rtpDesc
                = content.getFirstChildOfType(
                        RtpDescriptionPacketExtension.class);

            String mediaType
                = rtpDesc != null ? rtpDesc.getMedia() : content.getName();
            List<SourcePacketExtension> sourcePacketExtensions =
                    rtpDesc != null ?
                            rtpDesc.getChildExtensionsOfType(SourcePacketExtension.class) :
                            content.getChildExtensionsOfType(SourcePacketExtension.class);

            mediaSourceMap.put(
                mediaType, new CopyOnWriteArrayList<>(sourcePacketExtensions));
        }

        return new MediaSourceMap(mediaSourceMap);
    }

    private String sourcesToString(List<SourcePacketExtension> sources)
    {
        StringBuilder str = new StringBuilder();
        for (SourcePacketExtension source : sources)
        {
            str.append(source.toString()).append(" ");
        }
        return str.toString();
    }

    /**
     * Returns a map of Colibri content's names to lists of
     * <tt>SourcePacketExtension</tt> which reflect the state of this
     * <tt>MediaSourceMap</tt>.
     *
     * @return <tt>Map<String, List<SourcePacketExtension></tt> which reflects
     *         the state of this <tt>MediaSourceMap</tt>.
     */
    public Map<String, List<SourcePacketExtension>> toMap()
    {
        return Collections.unmodifiableMap(sources);
    }

    @Override
    public String toString()
    {
        StringBuilder str = new StringBuilder("Sources{");
        for (String mediaType : getMediaTypes())
        {
            str.append(" ").append(mediaType).append(": [");
            str.append(sourcesToString(getSourcesForMedia(mediaType)));
            str.append("]");
        }
        return str.append(" }@").append(hashCode()).toString();
    }
}
