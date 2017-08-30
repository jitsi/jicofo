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

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;

import org.jitsi.protocol.xmpp.util.*;
import org.jitsi.util.*;

import org.jivesoftware.smack.packet.*;

import java.util.*;

/**
 * Utility class that wraps the process of validating new sources and source groups
 * that are about to be added to {@link Participant}
 *
 * @author Pawel Domas
 */
public class SSRCValidator
{
    /**
     * The logger used by this class
     */
    private final static Logger classLogger
        = Logger.getLogger(SSRCValidator.class);

    /**
     * The logger used by this instance. It uses the log level delegate from
     * the logger passed to the constructor.
     */
    private final Logger logger;

    /**
     * Participant's endpoint ID used for printing log messages.
     */
    private final String endpointId;

    /**
     * The source map obtained from the participant which reflects current source
     * status. It's a clone and modifications done here do not affect
     * the version held by {@link Participant}.
     */
    private final MediaSourceMap sources;

    /**
     * Same as {@link #sources}, but for source groups.
     */
    private final MediaSourceGroupMap sourceGroups;

    /**
     * The limit sources count per media type allowed to be stored by
     * the {@link Participant} at a time.
     */
    private final int maxSourceCount;

    /**
     * Creates new <tt>SSRCValidator</tt>
     * @param endpointId participant's endpoint ID
     * @param sources participant's source map
     * @param sourceGroups participant's source group map
     * @param maxSourceCount the source limit, tells how many sources per media type
     * can be stored at a time.
     * @param logLevelDelegate a <tt>Logger</tt> which will be used as
     * the logging level delegate.
     */
    public SSRCValidator(String               endpointId,
                         MediaSourceMap sources,
                         MediaSourceGroupMap sourceGroups,
                         int maxSourceCount,
                         Logger               logLevelDelegate)
    {
        this.endpointId = endpointId;
        this.sources = sources.copyDeep();
        this.sourceGroups = sourceGroups.copy();
        this.maxSourceCount = maxSourceCount;
        this.logger = Logger.getLogger(classLogger, logLevelDelegate);
    }

    /**
     * Makes an attempt to add given sources and source groups to the current state.
     * It checks some constraints that prevent from injecting invalid
     * description into the conference:
     * 1. Allow SSRC value between 1 and 0xFFFFFFFF (note that 0 is a valid
     *    value, but it breaks WebRTC stack in Chrome, so not allowed here)
     * 2. Does not allow the same source to appear more than once per media type
     * 3. Truncates sources above the limit (configured in the constructor)
     * 4. Filters out SSRC parameters other than 'cname' and 'msid'
     * 5. Drop empty source groups
     * 6. Skips duplicated groups (the same semantics and contained sources)
     * 7. Looks for MSID conflicts between SSRCs which do not belong to the same
     *    group
     * 8. Makes sure that sources described by groups exist in media description
     *
     * @param newSources the sources to add
     * @param newGroups the groups to add
     *
     * @return see return value description of
     * {@link Participant#addSourcesAndGroupsFromContent(List)}.
     *
     * @throws InvalidSSRCsException see throws of
     * {@link Participant#addSourcesAndGroupsFromContent(List)}.
     */
    public Object[] tryAddSourcesAndGroups(MediaSourceMap newSources,
                                           MediaSourceGroupMap newGroups)
        throws InvalidSSRCsException
    {
        MediaSourceMap acceptedSources = new MediaSourceMap();
        for (String mediaType : newSources.getMediaTypes())
        {
            List<SourcePacketExtension> mediaSources
                = newSources.getSourcesForMedia(mediaType);

            for (SourcePacketExtension source : mediaSources)
            {
                if (!source.hasSSRC() && !source.hasRid())
                {
                    // SourcePacketExtension treats -1 as lack of SSRC
                    throw new InvalidSSRCsException(
                            "Source with no value was passed"
                                + " (parsed from negative ?)");
                }
                else if (source.hasSSRC())
                {
                    long ssrcValue = source.getSSRC();

                    // NOTE Technically SSRC == 0 is allowed, but it breaks Chrome
                    if (ssrcValue <= 0L || ssrcValue > 0xFFFFFFFFL)
                    {
                        throw new InvalidSSRCsException(
                                "Illegal SSRC value: " + ssrcValue);
                    }
                }

                // Check for duplicates
                String conflictingMediaType
                    = sources.getMediaTypeForSource(source);
                if (conflictingMediaType != null)
                {
                    throw new InvalidSSRCsException(
                        "Source "  + source.toString() + " is in "
                            + conflictingMediaType + " already");
                }
                // Check for SSRC limit exceeded
                else if (
                    sources.getSourcesForMedia(mediaType).size() >= maxSourceCount)
                {
                    logger.error(
                        "Too many sources signalled by "
                            + endpointId + " - dropping: " + source.toString());
                    // Abort - can't add any more SSRCs.
                    break;
                }

                SourcePacketExtension copy = source.copy();

                filterOutParams(copy);

                acceptedSources.addSource(mediaType, copy);
                this.sources.addSource(mediaType, copy);
            }
        }
        // Go over groups
        MediaSourceGroupMap acceptedGroups = new MediaSourceGroupMap();

        // Cross check if any source belongs to any existing group already
        for (String mediaType : newGroups.getMediaTypes())
        {
            for (SourceGroup groupToAdd
                : newGroups.getSourceGroupsForMedia(mediaType))
            {
                if (groupToAdd.isEmpty())
                {
                    logger.warn("Empty group signalled by: " + endpointId);
                    continue;
                }

                if (sourceGroups.containsGroup(mediaType, groupToAdd))
                {
                    logger.warn(
                        endpointId
                            + " is trying to add an existing group :"
                            + groupToAdd);
                }
                else
                {
                    acceptedGroups.addSourceGroup(mediaType, groupToAdd);
                    sourceGroups.addSourceGroup(mediaType, groupToAdd);
                }
            }
        }

        this.validateStreams();

        return new Object[] { acceptedSources, acceptedGroups };
    }

    private void filterOutParams(SourcePacketExtension copy)
    {
        Iterator<? extends PacketExtension> params
            = copy.getChildExtensions().iterator();
        while (params.hasNext())
        {
            PacketExtension ext = params.next();
            if (ext instanceof ParameterPacketExtension)
            {
                ParameterPacketExtension ppe = (ParameterPacketExtension) ext;
                if (!"cname".equalsIgnoreCase(ppe.getName()) &&
                    !"msid".equalsIgnoreCase(ppe.getName()))
                {
                    params.remove();
                }
            }
        }
    }

    private void validateStreams()
        throws InvalidSSRCsException
    {
        // Holds sources that belongs to any group
        MediaSourceMap groupedSources = new MediaSourceMap();

        // Go over every group and check if they have corresponding SSRCs
        for (String mediaType : sourceGroups.getMediaTypes())
        {
            List<SourceGroup> mediaGroups
                = sourceGroups.getSourceGroupsForMedia(mediaType);
            for (SourceGroup group : mediaGroups)
            {
                List<SourcePacketExtension> groupSources = group.getSources();
                // NOTE that empty groups are not allowed at this point and
                // should have been filtered out earlier
                String groupMSID = null;

                for (SourcePacketExtension source : groupSources)
                {
                    // Is there a corresponding SSRC that's in the SSRCs map ?
                    SourcePacketExtension sourceInMedia
                            = this.sources.findSource(mediaType, source);
                    if (sourceInMedia == null)
                    {
                        String errorMsg
                                = "Source " + source.toString() + " not found in "
                                + mediaType + " for group: " + group;
                        throw new InvalidSSRCsException(errorMsg);
                    }
                    if (source.hasSSRC())
                    {
                        long ssrcValue = source.getSSRC();
                        // Grouped SSRC needs to have some MSID
                        String msid = SSRCSignaling.getMsid(sourceInMedia);
                        if (StringUtils.isNullOrEmpty(msid))
                        {
                            throw new InvalidSSRCsException(
                                    "Grouped SSRC (" + ssrcValue + ") has no 'msid'");
                        }
                        // The first SSRC's MSID is used as group's MSID
                        if (groupMSID == null)
                        {
                            groupMSID = msid;
                        }
                        // Verify if MSID is the same across all SSRCs which belong
                        // to the same group
                        else if (!groupMSID.equals(msid))
                        {
                            throw new InvalidSSRCsException(
                                    "MSID mismatch detected in group " + group);
                        }
                    }

                    groupedSources.addSource(mediaType, source);
                }
            }
        }

        MediaSourceMap notGroupedSSRCs = this.sources.copyDeep();
        notGroupedSSRCs.remove(groupedSources);

        // Check for duplicated 'MSID's across each media type in
        // non grouped-sources
        for (String mediaType : notGroupedSSRCs.getMediaTypes())
        {
            Map<String, SourcePacketExtension> streamMap
                = new HashMap<>();

            List<SourcePacketExtension> mediaSSRCs
                = notGroupedSSRCs.getSourcesForMedia(mediaType);
            for (SourcePacketExtension ssrc : mediaSSRCs)
            {
                String msid = SSRCSignaling.getMsid(ssrc);
                if (msid != null)
                {
                    SourcePacketExtension conflictingSSRC
                        = streamMap.get(msid);
                    if (conflictingSSRC != null)
                    {
                        throw new InvalidSSRCsException(
                            "Not grouped SSRC " + ssrc.getSSRC()
                                + " has conflicting MSID '" + msid
                                + "' with " + conflictingSSRC.getSSRC());
                    }
                    else
                    {
                        streamMap.put(msid, ssrc);
                    }
                }
                // else
                // That could be recv-only SSRC (no MSID)
            }
        }
    }
}
