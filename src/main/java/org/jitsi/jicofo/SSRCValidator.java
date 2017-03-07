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
 * Utility class that wraps the process of validating new SSRCs and SSRC groups
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
     * The SSRC map obtained from the participant which reflects current SSRC
     * status. It's a clone and modifications done here do not affect
     * the version held by {@link Participant}.
     */
    private final MediaSSRCMap ssrcs;

    /**
     * Same as {@link #ssrcs}, but for SSRC groups.
     */
    private final MediaSSRCGroupMap ssrcGroups;

    /**
     * The limit SSRCs count per media type allowed to be stored by
     * the {@link Participant} at a time.
     */
    private final int maxSSRCCount;

    /**
     * Creates new <tt>SSRCValidator</tt>
     * @param endpointId participant's endpoint ID
     * @param ssrcMap participant's SSRC map
     * @param ssrcGroups participant's SSRC group map
     * @param maxSSRCCount the SSRC limit, tells how many SSRC per media type
     * can be stored at a time.
     * @param logLevelDelegate a <tt>Logger</tt> which will be used as
     * the logging level delegate.
     */
    public SSRCValidator(String               endpointId,
                         MediaSSRCMap         ssrcMap,
                         MediaSSRCGroupMap    ssrcGroups,
                         int                  maxSSRCCount,
                         Logger               logLevelDelegate)
    {
        this.endpointId = endpointId;
        this.ssrcs = ssrcMap.copyDeep();
        this.ssrcGroups = ssrcGroups.copy();
        this.maxSSRCCount = maxSSRCCount;
        this.logger = Logger.getLogger(classLogger, logLevelDelegate);
    }

    /**
     * Makes an attempt to add given SSRC and SSRC groups to the current state.
     * It checks some constraints that prevent from injecting invalid
     * description into the conference:
     * 1. Allow SSRC value between 1 and 0xFFFFFFFF (note that 0 is a valid
     *    value, but it breaks WebRTC stack in Chrome, so not allowed here)
     * 2. Does not allow the same SSRC to appear more than once per media type
     * 3. Truncates SSRCs above the limit (configured in the constructor)
     * 4. Filters out SSRC parameters other than 'cname' and 'msid'
     * 5. Drop empty SSRC groups
     * 6. Skips duplicated groups (the same semantics and contained SSRCs)
     * 7. Looks for MSID conflicts between SSRCs which do not belong to the same
     *    group
     * 8. Makes sure that SSRCs described by groups exist in media description
     *
     * @param newSSRCs the SSRCs to add
     * @param newGroups the SSRC groups to add
     *
     * @return see return value description of
     * {@link Participant#addSSRCsAndGroupsFromContent(List)}.
     *
     * @throws InvalidSSRCsException see throws of
     * {@link Participant#addSSRCsAndGroupsFromContent(List)}.
     */
    public Object[] tryAddSSRCsAndGroups(MediaSSRCMap         newSSRCs,
                                         MediaSSRCGroupMap    newGroups)
        throws InvalidSSRCsException
    {
        MediaSSRCMap acceptedSSRCs = new MediaSSRCMap();
        for (String mediaType : newSSRCs.getMediaTypes())
        {
            List<SourcePacketExtension> mediaSsrcs
                = newSSRCs.getSSRCsForMedia(mediaType);

            for (SourcePacketExtension ssrcPe : mediaSsrcs)
            {
                long ssrcValue = ssrcPe.getSSRC();

                // NOTE Technically SSRC == 0 is allowed, but it breaks Chrome
                if (ssrcValue <= 0L || ssrcValue > 0xFFFFFFFFL)
                {
                    throw new InvalidSSRCsException(
                        "Illegal SSRC value: " + ssrcValue);
                }

                // Check for duplicates
                String conflictingMediaType
                    = ssrcs.findSSRCsMediaType(ssrcValue);
                if (conflictingMediaType != null)
                {
                    throw new InvalidSSRCsException(
                        "SSRC "  + ssrcValue + " is in "
                            + conflictingMediaType + " already");
                }
                // Check for SSRC limit exceeded
                else if (
                    ssrcs.getSSRCsForMedia(mediaType).size() >= maxSSRCCount)
                {
                    logger.error(
                        "Too many SSRCs signalled by "
                            + endpointId + " - dropping: " + ssrcValue);
                    // Abort - can't add any more SSRCs.
                    break;
                }

                SourcePacketExtension copy = ssrcPe.copy();

                filterOutParams(copy);

                acceptedSSRCs.addSSRC(mediaType, copy);
                this.ssrcs.addSSRC(mediaType, copy);
            }
        }
        // Go over groups
        MediaSSRCGroupMap acceptedGroups = new MediaSSRCGroupMap();

        // Cross check if any SSRC belongs to any existing group already
        for (String mediaType : newGroups.getMediaTypes())
        {
            for (SSRCGroup groupToAdd
                : newGroups.getSSRCGroupsForMedia(mediaType))
            {
                if (groupToAdd.isEmpty())
                {
                    logger.warn("Empty group signalled by: " + endpointId);
                    continue;
                }

                if (ssrcGroups.containsGroup(mediaType, groupToAdd))
                {
                    logger.warn(
                        endpointId
                            + " is trying to add an existing group :"
                            + groupToAdd);
                }
                else
                {
                    acceptedGroups.addSSRCGroup(mediaType, groupToAdd);
                    ssrcGroups.addSSRCGroup(mediaType, groupToAdd);
                }
            }
        }

        this.validateStreams();

        return new Object[] { acceptedSSRCs, acceptedGroups };
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
        // Holds SSRCs that belongs to any group
        MediaSSRCMap groupedSSRCs = new MediaSSRCMap();

        // Go over every group and check if they have corresponding SSRCs
        for (String mediaType : ssrcGroups.getMediaTypes())
        {
            List<SSRCGroup> mediaGroups
                = ssrcGroups.getSSRCGroupsForMedia(mediaType);
            for (SSRCGroup group : mediaGroups)
            {
                List<SourcePacketExtension> groupSSRCs = group.getSources();
                // NOTE that empty groups are not allowed at this point and
                // should have been filtered out earlier
                String groupMSID = null;

                for (SourcePacketExtension ssrc : groupSSRCs)
                {
                    long ssrcValue = ssrc.getSSRC();
                    // Is there a corresponding SSRC that's in the SSRCs map ?
                    SourcePacketExtension ssrcInMedia
                        = this.ssrcs.findSSRC(mediaType, ssrcValue);
                    if (ssrcInMedia == null)
                    {
                        String errorMsg
                            = "SSRC " + ssrcValue + " not found in "
                                + mediaType + " for group: " + group;
                        throw new InvalidSSRCsException(errorMsg);
                    }
                    // Grouped SSRC needs to have some MSID
                    String msid = SSRCSignaling.getStreamId(ssrcInMedia);
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

                    groupedSSRCs.addSSRC(mediaType, ssrc);
                }
            }
        }

        MediaSSRCMap notGroupedSSRCs = this.ssrcs.copyDeep();
        notGroupedSSRCs.remove(groupedSSRCs);

        // Check for duplicated 'MSID's across each media type in
        // non grouped-ssrcs
        for (String mediaType : notGroupedSSRCs.getMediaTypes())
        {
            Map<String, SourcePacketExtension> streamMap
                = new HashMap<>();

            List<SourcePacketExtension> mediaSSRCs
                = notGroupedSSRCs.getSSRCsForMedia(mediaType);
            for (SourcePacketExtension ssrc : mediaSSRCs)
            {
                String streamId = SSRCSignaling.getStreamId(ssrc);
                if (streamId != null)
                {
                    SourcePacketExtension conflictingSSRC
                        = streamMap.get(streamId);
                    if (conflictingSSRC != null)
                    {
                        throw new InvalidSSRCsException(
                            "Not grouped SSRC " + ssrc.getSSRC()
                                + " has conflicting MSID '" + streamId
                                + "' with " + conflictingSSRC.getSSRC());
                    }
                    else
                    {
                        streamMap.put(streamId, ssrc);
                    }
                }
                // else
                // That could be recv-only SSRC (no MSID)
            }
        }
    }
}
