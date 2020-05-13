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

import org.jitsi.utils.*;
import org.jitsi.xmpp.extensions.colibri.*;
import org.jitsi.xmpp.extensions.jingle.*;

import org.jitsi.protocol.xmpp.util.*;
import org.jitsi.utils.logging.*;

import org.jivesoftware.smack.packet.*;
import org.jxmpp.jid.*;

import java.util.*;
import java.util.stream.*;

import static org.apache.commons.lang3.StringUtils.*;

/**
 * Utility class that wraps the process of validating new sources and source
 * groups that are to be added to the conference.
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
     * Each validation happens when a participant sends new sources and one
     * instance of {@link SSRCValidator} is only good for one such validation.
     * This field stores participant's endpoint ID used for printing log
     * messages.
     */
    private final String endpointId;

    /**
     * The source map obtained from the participant which reflects current
     * source status. It's a clone and modifications done here do not affect
     * the version held by {@link Participant}.
     */
    private final MediaSourceMap sources;

    /**
     * Same as {@link #sources}, but for source groups.
     */
    private final MediaSourceGroupMap sourceGroups;

    /**
     * The limit sources count per media type allowed to be stored by
     * each {@link Participant} at a time.
     */
    private final int maxSourceCount;

    /**
     * Filters out FID groups that do belong to any simulcast grouping.
     *
     * @param simGroupings the list of all {@link SimulcastGrouping}s.
     * @param fidGroups the list of all FID groups even these which are part of
     *        the <tt>simGroupings</tt>.
     *
     * @return a list of FID groups that are not part of any SIM grouping.
     */
    static private List<SourceGroup> getIndependentFidGroups(
            List<SimulcastGrouping>    simGroupings,
            List<SourceGroup>          fidGroups)
    {
        if (simGroupings.isEmpty())
        {
            // Nothing to be done here...
            return new ArrayList<>(fidGroups);
        }

        return fidGroups.stream()
            .filter(
                fidGroup -> simGroupings.stream()
                    .noneMatch(
                        simGroup ->
                            simGroup.belongsToSimulcastGrouping(fidGroup))
                ).collect(Collectors.toList());
    }

    /**
     * Checks if there are no MSID conflicts across all independent FID groups.
     *
     * @param independentFidGroups the list of all independent FID groups (that
     *        do not belong to any other higher level grouping).
     *
     * @throws InvalidSSRCsException in case of MSID conflict
     */
    static private void verifyNoMsidConflictsAcrossFidGroups(
            List<SourceGroup> independentFidGroups)
        throws InvalidSSRCsException
    {
        for (SourceGroup fidGroup : independentFidGroups)
        {
            // NOTE at this point we're sure that every source has MSID
            String fidGroupMsid = fidGroup.getGroupMsid();
            List<SourceGroup> withTheMsid
                = SSRCSignaling.selectWithMsid(
                        independentFidGroups, fidGroupMsid);

            for (SourceGroup conflictingGroup : withTheMsid)
            {
                if (conflictingGroup != fidGroup)
                {
                    throw new InvalidSSRCsException(
                            "MSID conflict across FID groups: "
                                + fidGroupMsid + ", " + conflictingGroup
                                + " conflicts with group " + fidGroup);
                }
            }
        }
    }

    /**
     * Checks if there are no MSID conflicts across simulcast groups.
     *
     * @param mediaType the media type to be checked in this call
     * @param groupedSources the map holding all sources which belong to any
     *        group for all media types.
     * @param simGroupings the list of all {@link SimulcastGrouping}s for
     *        the <tt>mediaType</tt>.
     *
     * @throws InvalidSSRCsException in case of MSID conflict
     */
    static private void verifyNoMsidConflictsAcrossSimGroupings(
            String                     mediaType,
            MediaSourceMap             groupedSources,
            List<SimulcastGrouping>    simGroupings)
        throws InvalidSSRCsException
    {
        for (SimulcastGrouping simGrouping : simGroupings)
        {
            String simulcastMsid = simGrouping.getSimulcastMsid();

            if (simGrouping.isUsingRidSignaling())
            {
                // Skip RID simulcast group
                continue;
            }
            else if (isBlank(simulcastMsid))
            {
                throw new InvalidSSRCsException(
                        "No MSID in simulcast group: " + simGrouping);
            }

            List<SourcePacketExtension> sourcesWithTheMsid
                = groupedSources.findSourcesWithMsid(
                        mediaType, simulcastMsid);

            for (SourcePacketExtension src : sourcesWithTheMsid)
            {
                if (!simGrouping.belongsToSimulcastGrouping(src))
                {
                    throw new InvalidSSRCsException(
                        "MSID conflict across SIM groups: "
                            + simulcastMsid + ", " + src
                            + " conflicts with group " + simGrouping);
                }
            }
        }
    }

    /**
     * Creates new <tt>SSRCValidator</tt>
     * @param endpointId participant's endpoint ID for whom the new
     * sources/groups will be validated.
     * @param sources the map which holds sources of the whole conference.
     * @param sourceGroups the map which holds source groups currently present
     * in the conference.
     * @param maxSourceCount the source limit, tells how many sources per media
     * type can be stored at a time by each conference participant.
     * @param logLevelDelegate a <tt>Logger</tt> which will be used as
     * the logging level delegate.
     */
    public SSRCValidator(String              endpointId,
                         MediaSourceMap      sources,
                         MediaSourceGroupMap sourceGroups,
                         int                 maxSourceCount,
                         Logger              logLevelDelegate)
    {
        this.endpointId = endpointId;
        this.sources = sources.copyDeep();
        this.sourceGroups = sourceGroups.copy();
        this.maxSourceCount = maxSourceCount;
        this.logger = Logger.getLogger(classLogger, logLevelDelegate);
    }

    /**
     * Checks how many sources are current in the conference for given
     * participant.
     *
     * @param owner An owner's JID (can be <tt>null</tt> to check for not owned
     * sources)
     * @param mediaType The type of the media for which sources will be counted.
     * @return how many sources are currently in the conference source map for
     * given media type and owner's JID.
     */
    private long getSourceCountForOwner(Jid owner, String mediaType)
    {
        return this.sources.getSourcesForMedia(mediaType)
            .stream()
            .filter(
                source -> Objects.equals(
                    SSRCSignaling.getSSRCOwner(source), owner))
            .count();

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
     * @return an array of two objects where first one is <tt>MediaSourceMap</tt>
     * contains the sources that have been accepted and the second one is
     * <tt>MediaSourceGroupMap</tt> with <tt>SourceGroup</tt>s accepted by this
     * validator instance.
     *
     * @throws InvalidSSRCsException if a critical problem has been found
     * with the new sources/groups which would probably result in
     * "setRemoteDescription" error on the client.
     */
    public Object[] tryAddSourcesAndGroups(
            MediaSourceMap newSources, MediaSourceGroupMap newGroups)
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

                // Check for Source limit exceeded
                Jid owner = SSRCSignaling.getSSRCOwner(source);
                long sourceCount = getSourceCountForOwner(owner, mediaType);
                if (sourceCount >= maxSourceCount)
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

    /**
     * Makes an attempt to remove given sources and source groups from
     * the current state.
     *
     * @param sourcesToRemove the sources to be removed
     * @param groupsToRemove the groups to be removed
     *
     * @return an array of two objects where first one is <tt>MediaSourceMap</tt>
     * contains the sources that have been removed and the second one is
     * <tt>MediaSourceGroupMap</tt> with <tt>SourceGroup</tt>s removed by this
     * validator instance.
     *
     * @throws InvalidSSRCsException if a critical problem has been found
     * after sources/groups removal which would probably would result in
     * "setRemoteDescription" error on the client.
     */
    public Object[] tryRemoveSourcesAndGroups(
            MediaSourceMap sourcesToRemove,
            MediaSourceGroupMap groupsToRemove)
        throws InvalidSSRCsException
    {
        MediaSourceMap removedSources = sources.remove(sourcesToRemove);
        MediaSourceGroupMap removedGroups = sourceGroups.remove(groupsToRemove);

        this.validateStreams();

        return new Object[] { removedSources, removedGroups };
    }

    private void filterOutParams(SourcePacketExtension copy)
    {
        Iterator<? extends ExtensionElement> params
            = copy.getChildExtensions().iterator();
        while (params.hasNext())
        {
            ExtensionElement ext = params.next();
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
        // Migrate source attributes from SourcePacketExtensions stored in
        // the media section to SourcePacketExtensions stored by the groups
        // directly in order to simplify the stream validation process.
        // The reason for that is that <tt>SourcePacketExtension</tt>s stored
        // in groups are empty and contain only SSRC number without any
        // parameters. Without this step we'd have to access separate collection
        // to check source's parameters like MSID.
        SSRCSignaling.copySourceParamsToGroups(sourceGroups, sources);

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
                String groupMSID = group.getGroupMsid();

                for (SourcePacketExtension source : groupSources)
                {
                    if (source.hasSSRC())
                    {
                        String msid = SSRCSignaling.getMsid(source);
                        // Grouped SSRC needs to have a valid MSID
                        if (isBlank(groupMSID))
                        {
                            throw new InvalidSSRCsException(
                                    "Grouped " + source + " has no 'msid'");
                        }
                        // Verify if MSID is the same across all SSRCs which
                        // belong to the same group
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

        // Verify SIM/FID grouping
        for (String mediaType : sourceGroups.getMediaTypes())
        {
            // FIXME migrate logic to use MediaType instead of String
            if (!MediaType.VIDEO.toString().equalsIgnoreCase(mediaType))
            {
                // Verify Simulcast only for the video media type
                continue;
            }

            List<SimulcastGrouping> simGroupings;

            try
            {
                simGroupings = sourceGroups.findSimulcastGroupings();
            }
            // If groups are in invalid state a SIM grouping may fail to
            // initialize with IllegalArgumentException
            catch (IllegalArgumentException exc)
            {
                throw new InvalidSSRCsException(exc.getMessage());
            }

            // Check if this SIM group's MSID does not appear in any other
            // simulcast grouping
            verifyNoMsidConflictsAcrossSimGroupings(
                    mediaType, groupedSources, simGroupings);

            // Check for MSID conflicts across FID groups that do not belong to
            // any Simulcast grouping.
            List<SourceGroup> fidGroups = sourceGroups.getRtxGroups();
            List<SourceGroup> independentFidGroups
                = getIndependentFidGroups(simGroupings, fidGroups);

            verifyNoMsidConflictsAcrossFidGroups(independentFidGroups);
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
