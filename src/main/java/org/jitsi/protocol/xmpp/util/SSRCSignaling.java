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

import org.jitsi.utils.*;
import org.jitsi.xmpp.extensions.colibri.*;
import org.jitsi.xmpp.extensions.jingle.*;
import org.jitsi.xmpp.extensions.jitsimeet.*;

import org.jitsi.jicofo.*;

import org.jivesoftware.smack.packet.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;
import org.jxmpp.stringprep.*;

import java.util.*;

import static org.apache.commons.lang3.StringUtils.*;

/**
 * Class gathers utility method related to SSRC signaling.
 *
 * TODO it feels like this utility thing is a candidate for SSRC class wrapping
 * <tt>SourcePacketExtension</tt>
 *
 * @author Pawel Domas
 */
public class SSRCSignaling
{
    /**
     * The constant value used as owner attribute value of
     * {@link SSRCInfoPacketExtension} for the SSRC which belongs to the JVB.
     */
    public static final Jid SSRC_OWNER_JVB;

    static
    {
        try
        {
            SSRC_OWNER_JVB = JidCreate.from("jvb");
        }
        catch (XmppStringprepException e)
        {
            // cannot happen
            throw new RuntimeException(e);
        }
    }

    /**
     * Copies value of "<parameter>" SSRC child element. The parameter to be
     * copied must exist in both source and destination SSRCs.
     * @param dst target <tt>SourcePacketExtension</tt> to which we want copy
     *            parameter value.
     * @param src origin <tt>SourcePacketExtension</tt> from which we want to
     *            copy parameter value.
     * @param name the name of the parameter to copy.
     */
    private static void copyParamAttr( SourcePacketExtension    dst,
                                      SourcePacketExtension    src,
                                      String                  name)
    {
        ParameterPacketExtension srcParam = getParam(src, name);
        ParameterPacketExtension dstParam;
        if (srcParam != null && (dstParam = getParam(dst, name)) != null)
        {
            dstParam.setValue(srcParam.getValue());
        }
    }

    /**
     * Replaces all instances of {@link SourcePacketExtension}s held by
     * {@link SourceGroupPacketExtension}s in the given
     * {@link MediaSourceGroupMap} with the corresponding ones from the given
     * {@link MediaSourceMap}<tt></tt>.
     *
     * @param groups <tt>MediaSourceGroupMap</tt>
     * @param sources <tt>MediaSourceMap</tt>
     *
     * @throws InvalidSSRCsException if there's no corresponding
     * {@link SourcePacketExtension} found in the <tt>sources</tt> for any
     * source in the <tt>groups</tt>.
     */
    static public void copySourceParamsToGroups(
            MediaSourceGroupMap    groups,
            MediaSourceMap         sources)
        throws InvalidSSRCsException
    {
        for (String mediaType : groups.getMediaTypes())
        {
            List<SourceGroup> mediaGroups
                = groups.getSourceGroupsForMedia(mediaType);
            List<SourceGroup> newMediaGroups
                = new ArrayList<>(mediaGroups.size());

            for (SourceGroup group : mediaGroups)
            {
                List<SourcePacketExtension> groupSources = group.getSources();
                List<SourcePacketExtension> newSources
                    = new ArrayList<>(groupSources.size());

                for (SourcePacketExtension srcInGroup : groupSources)
                {
                    SourcePacketExtension sourceInMedia
                        = sources.findSource(mediaType, srcInGroup);

                    if (sourceInMedia == null)
                    {
                        throw new InvalidSSRCsException(
                                "Source " + srcInGroup
                                    + " not found in '" + mediaType
                                    + "' for group: " + group);
                    }

                    newSources.add(sourceInMedia);
                }

                newMediaGroups.add(
                        new SourceGroup(group.getSemantics(), newSources));
            }

            mediaGroups.clear();
            mediaGroups.addAll(newMediaGroups);
        }
    }

    /**
     * Call will remove all {@link ParameterPacketExtension}s from all
     * <tt>SourcePacketExtension</tt>s stored in given <tt>MediaSourceMap</tt>.
     *
     * @param ssrcMap the <tt>MediaSourceMap</tt> which contains the SSRC packet
     * extensions to be stripped out of their parameters.
     */
    public static void deleteSSRCParams(MediaSourceMap ssrcMap)
    {
        for (String media : ssrcMap.getMediaTypes())
        {
            for (SourcePacketExtension ssrc : ssrcMap.getSourcesForMedia(media))
            {
                deleteSSRCParams(ssrc);
            }
        }
    }

    /**
     * Removes all child <tt>ParameterPacketExtension</tt>s from given
     * <tt>SourcePacketExtension</tt>.
     *
     * @param ssrcPe the instance of <tt>SourcePacketExtension</tt> which will
     * be stripped off all parameters.
     */
    private static void deleteSSRCParams(SourcePacketExtension ssrcPe)
    {
        List<? extends ExtensionElement> peList = ssrcPe.getChildExtensions();
        peList.removeAll(ssrcPe.getParameters());
    }

    /**
     * Finds the first SSRC in the list with a valid stream ID('msid').
     * The 'default' stream id is not considered a valid one.
     *
     * @param ssrcs the list of <tt>SourcePacketExtension</tt> to be searched.
     *
     * @return the first <tt>SourcePacketExtension</tt> with a valid media
     *         stream id or <tt>null</tt> if there aren't any such streams
     *         in the list.
     */
    public static SourcePacketExtension getFirstWithMSID(
            List<SourcePacketExtension> ssrcs)
    {
        for (SourcePacketExtension ssrc : ssrcs)
        {
            String streamId = getStreamId(ssrc);
            if (streamId != null && !"default".equalsIgnoreCase(streamId))
            {
                return ssrc;
            }
        }
        return null;
    }

    private static List<SourcePacketExtension> getAllWithMSID(
        List<SourcePacketExtension> ssrcs)
    {
        ArrayList<SourcePacketExtension> result = new ArrayList<>(ssrcs.size());
        for (SourcePacketExtension ssrc : ssrcs)
        {
            String streamId = getStreamId(ssrc);
            if (streamId != null && !"default".equalsIgnoreCase(streamId))
            {
                result.add(ssrc);
            }
        }
        return result;
    }

    /**
     * Obtains the MSID attribute value of given {@link SourcePacketExtension}.
     * @param source {@link SourcePacketExtension}
     * @return <tt>String</tt> value of the MSID attribute of given source
     * extension or <tt>null</tt> if it's either empty or not present.
     */
    public static String getMsid(SourcePacketExtension source)
    {
        ParameterPacketExtension msid = getParam(source, "msid");
        if (msid != null && isNotBlank(msid.getValue()))
        {
            return msid.getValue();
        }
        else
        {
            return null;
        }
    }

    /**
     * Obtains <tt>ParameterPacketExtension</tt> for given name(if it exists).
     * @param ssrc the <tt>SourcePacketExtension</tt> to be searched for
     *             parameter
     * @param name the name of the parameter to be found
     * @return <tt>ParameterPacketExtension</tt> instance for given
     *         <tt>name</tt> or <tt>null</tt> if not found.
     */
    private static ParameterPacketExtension getParam(SourcePacketExtension ssrc,
                                                     String                name)
    {
        for(ParameterPacketExtension param : ssrc.getParameters())
        {
            if (name.equals(param.getName()))
                return param;
        }
        return null;
    }

    /**
     * Gets the owner of <tt>SourcePacketExtension</tt> in jitsi-meet context
     * signaled through {@link SSRCInfoPacketExtension}.
     * @param ssrcPe the <tt>SourcePacketExtension</tt> instance for which we
     *               want to find an owner.
     * @return MUC {@link Jid} of the user who owns this source or <tt>null</tt>
     * if it's not owned by anyone.
     */
    public static Jid getSSRCOwner(SourcePacketExtension ssrcPe)
    {
        SSRCInfoPacketExtension ssrcInfo
            = ssrcPe.getFirstChildOfType(SSRCInfoPacketExtension.class);

        return ssrcInfo != null ? ssrcInfo.getOwner() : null;
    }

    /**
     * Get's WebRTC stream ID extracted from "msid" SSRC parameter.
     * @param ssrc <tt>SourcePacketExtension</tt> that describes the SSRC for
     *             which we want to obtain WebRTC stream ID.
     * @return WebRTC stream ID that is the first part of "msid" SSRC parameter.
     */
    public static String getStreamId(SourcePacketExtension ssrc)
    {
        String msid = getMsid(ssrc);

        if (msid == null)
            return null;

        String[] streamAndTrack = msid.split(" ");
        String streamId = streamAndTrack.length == 2 ? streamAndTrack[0] : null;
        if (streamId != null) {
            streamId = streamId.trim();
            if (streamId.isEmpty()) {
                streamId = null;
            }
        }
        return streamId;
    }

    /**
     * Get's WebRTC track ID extracted from "msid" SSRC parameter.
     * @param ssrc <tt>SourcePacketExtension</tt> that describes the SSRC for
     *             which we want to obtain WebRTC stream ID.
     * @return WebRTC track ID that is the second part of "msid" SSRC parameter.
     */
    private static String getTrackId(SourcePacketExtension ssrc)
    {
        String msid = getMsid(ssrc);

        if (msid == null)
            return null;

        String[] streamAndTrack = msid.split(" ");
        String trackId = streamAndTrack.length == 2 ? streamAndTrack[1] : null;
        if (trackId != null) {
            trackId = trackId.trim();
            if (trackId.isEmpty()) {
                trackId = null;
            }
        }
        return trackId;
    }

    public static String getVideoType(SourcePacketExtension ssrcPe)
    {
        SSRCInfoPacketExtension ssrcInfo
            = ssrcPe.getFirstChildOfType(SSRCInfoPacketExtension.class);

        return ssrcInfo != null ? ssrcInfo.getVideoType() : null;
    }

    /**
     * Merges the first valid video stream into the first valid audio stream
     * described in <tt>MediaSourceMap</tt>. A valid media stream is the one that
     * has well defined "stream ID" as in the description of
     * {@link #getFirstWithMSID(List)} method.
     *
     * @param peerSSRCs the map of media SSRC to be modified.
     *
     * @return <tt>true</tt> if the streams have been merged or <tt>false</tt>
     * otherwise.
     */
    public static boolean mergeVideoIntoAudio(MediaSourceMap peerSSRCs)
    {
        List<SourcePacketExtension> audioSSRCs
            = peerSSRCs.getSourcesForMedia(MediaType.AUDIO.toString());

        // We want to sync video stream with the first valid audio stream
        SourcePacketExtension audioSSRC = getFirstWithMSID(audioSSRCs);
        // Nothing to sync to
        if (audioSSRC == null)
            return false;

        String audioStreamId = getStreamId(audioSSRC);
        if (audioStreamId == null)
        {
            // No valid audio stream
            return false;
        }

        // Find first video SSRC with non-empty stream ID and different
        // than 'default' which is sometimes used when unspecified
        List<SourcePacketExtension> videoSSRCs
            = peerSSRCs.getSourcesForMedia(MediaType.VIDEO.toString());

        boolean merged = false;
        // There are multiple video SSRCs in simulcast
        // FIXME this will not work with more than 1 video stream
        //       per participant, as it will merge them into single stream
        videoSSRCs = getAllWithMSID(videoSSRCs);
        // Will merge video stream SSRCs by modifying their msid and copying
        // cname and label
        for (SourcePacketExtension videoSSRC : videoSSRCs)
        {
            // Note that videoMsid is never null
            // (it's checked in getAllWithMSID)
            ParameterPacketExtension videoMsid = getParam(videoSSRC, "msid");
            String videoTrackId = getTrackId(videoSSRC);
            if (videoTrackId != null)
            {
                // Copy cname and label
                copyParamAttr(videoSSRC, audioSSRC, "cname");
                copyParamAttr(videoSSRC, audioSSRC, "mslabel");

                videoMsid.setValue(audioStreamId + " " + videoTrackId);
                merged = true;
            }
        }
        return merged;
    }

    /**
     * Does map the SSRCs found in given Jingle content list on per owner basis.
     *
     * @param contents the Jingle contents list that describes media SSRCs.
     *
     * @return a <tt>Map<String,MediaSourceMap></tt> which is the SSRC to owner
     *         mapping of the SSRCs contained in given Jingle content list.
     *         An owner comes form the {@link SSRCInfoPacketExtension} included
     *         as a child of the {@link SourcePacketExtension}.
     */
    public static Map<Jid, MediaSourceMap> ownerMapping(
            List<ContentPacketExtension> contents)
    {
        Map<Jid, MediaSourceMap> ownerMapping = new HashMap<>();
        for (ContentPacketExtension content : contents)
        {
            String media = content.getName();
            MediaSourceMap mediaSourceMap
                = MediaSourceMap.getSourcesFromContent(contents);

            for (SourcePacketExtension ssrc
                    : mediaSourceMap.getSourcesForMedia(media))
            {
                Jid owner = getSSRCOwner(ssrc);
                MediaSourceMap ownerMap = ownerMapping.get(owner);

                // Create if not found
                if (ownerMap == null)
                {
                    ownerMap = new MediaSourceMap();
                    ownerMapping.put(owner, ownerMap);
                }

                ownerMap.addSource(media, ssrc);
            }
        }
        return ownerMapping;
    }

    /**
     * From the given list of {@link SourceGroup}s select those that share
     * the given MSID value.
     *
     * @param groups a {@link List} of {@link SourceGroup} to be filtered out.
     * @param groupMsid a {@link String} with the MSID value to be used as
     *        selector.
     *
     * @return a {@link List} of {@link SourceGroup}.
     */
    public static List<SourceGroup> selectWithMsid(
        List<SourceGroup>    groups,
        String               groupMsid)
    {
        if (isBlank(groupMsid))
        {
            throw new IllegalArgumentException("Null or empty 'groupMsid'");
        }

        // FIXME change once migrated to Java 8
        // return groups.stream()
        //     .filter(group -> group.getGroupMsid().equalsIgnoreCase(groupMsid)
        //     .collect(Collectors.toList());

        List<SourceGroup> result = new LinkedList<>();

        for (SourceGroup group : groups)
        {
            if (groupMsid.equalsIgnoreCase(group.getGroupMsid()))
            {
                result.add(group);
            }
        }

        return result;
    }

    public static void setSSRCOwner(SourcePacketExtension ssrcPe, Jid owner)
    {
        SSRCInfoPacketExtension ssrcInfo
            = ssrcPe.getFirstChildOfType(SSRCInfoPacketExtension.class);

        if (ssrcInfo == null)
        {
            ssrcInfo = new SSRCInfoPacketExtension();
            ssrcPe.addChildExtension(ssrcInfo);
        }

        ssrcInfo.setOwner(owner);
    }

    public static void setSSRCVideoType( SourcePacketExtension     ssrcPe,
                                         String                 videoType)
    {
        SSRCInfoPacketExtension ssrcInfo
            = ssrcPe.getFirstChildOfType(SSRCInfoPacketExtension.class);

        if (ssrcInfo == null)
        {
            ssrcInfo = new SSRCInfoPacketExtension();
            ssrcPe.addChildExtension(ssrcInfo);
        }

        ssrcInfo.setVideoType(videoType);
    }
}
