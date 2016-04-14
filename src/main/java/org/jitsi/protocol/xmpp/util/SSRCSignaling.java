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
import net.java.sip.communicator.impl.protocol.jabber.extensions.jitsimeet.*;

import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;

import org.jivesoftware.smack.packet.*;

import java.util.*;

/**
 * Class gathers utility method related to SSRC signaling.
 *
 * @author Pawel Domas
 */
public class SSRCSignaling
{
    /**
     * Copies value of "<parameter>" SSRC child element. The parameter to be
     * copied must exist in both source and destination SSRCs.
     * @param dst target <tt>SourcePacketExtension</tt> to which we want copy
     *            parameter value.
     * @param src origin <tt>SourcePacketExtension</tt> from which we want to
     *            copy parameter value.
     * @param name the name of the parameter to copy.
     */
    public static void copyParamAttr( SourcePacketExtension    dst,
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
     * Call will remove all {@link ParameterPacketExtension}s from all
     * <tt>SourcePacketExtension</tt>s stored in given <tt>MediaSSRCMap</tt>.
     *
     * @param ssrcMap the <tt>MediaSSRCMap</tt> which contains the SSRC packet
     * extensions to be stripped out of their parameters.
     */
    public static void deleteSSRCParams(MediaSSRCMap ssrcMap)
    {
        for (String media : ssrcMap.getMediaTypes())
        {
            for (SourcePacketExtension ssrc : ssrcMap.getSSRCsForMedia(media))
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
    public static void deleteSSRCParams(SourcePacketExtension ssrcPe)
    {
        List<? extends PacketExtension> peList = ssrcPe.getChildExtensions();
        peList.removeAll(ssrcPe.getParameters());
    }

    /**
     * Obtains <tt>ParameterPacketExtension</tt> for given name(if it exists).
     * @param ssrc the <tt>SourcePacketExtension</tt> to be searched for
     *             parameter
     * @param name the name of the parameter to be found
     * @return <tt>ParameterPacketExtension</tt> instance for given
     *         <tt>name</tt> or <tt>null</tt> if not found.
     */
    public static ParameterPacketExtension getParam( SourcePacketExtension ssrc,
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
     * @return MUC jid of the user who own this SSRC.
     */
    public static String getSSRCOwner(SourcePacketExtension ssrcPe)
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
        ParameterPacketExtension msid = getParam(ssrc, "msid");

        if (msid == null || StringUtils.isNullOrEmpty(msid.getValue()))
            return null;

        String[] streamAndTrack = msid.getValue().split(" ");
        return streamAndTrack.length == 2 ? streamAndTrack[0] : null;
    }

    /**
     * Get's WebRTC track ID extracted from "msid" SSRC parameter.
     * @param ssrc <tt>SourcePacketExtension</tt> that describes the SSRC for
     *             which we want to obtain WebRTC stream ID.
     * @return WebRTC track ID that is the second part of "msid" SSRC parameter.
     */
    public static String getTrackId(SourcePacketExtension ssrc)
    {
        ParameterPacketExtension msid = getParam(ssrc, "msid");

        if (msid == null || StringUtils.isNullOrEmpty(msid.getValue()))
            return null;

        String[] streamAndTrack = msid.getValue().split(" ");
        return streamAndTrack.length == 2 ? streamAndTrack[1] : null;
    }

    public static String getVideoType(SourcePacketExtension ssrcPe)
    {
        SSRCInfoPacketExtension ssrcInfo
            = ssrcPe.getFirstChildOfType(SSRCInfoPacketExtension.class);

        return ssrcInfo != null ? ssrcInfo.getVideoType() : null;
    }

    /**
     * Merges first audio SSRC into the first video stream described by
     * <tt>MediaSSRCMap</tt>.
     *
     * @param peerSSRCs the map of media SSRC to be modified.
     *
     * @return <tt>true</tt> if streams were merged.
     */
    public static boolean mergeAudioIntoVideo(MediaSSRCMap peerSSRCs)
    {
        List<SourcePacketExtension> audioSSRCs
            = peerSSRCs.getSSRCsForMedia(MediaType.AUDIO.toString());

        if (audioSSRCs.isEmpty())
            return false;

        List<SourcePacketExtension> videoSSRCs
            = peerSSRCs.getSSRCsForMedia(MediaType.VIDEO.toString());

        if (videoSSRCs.isEmpty())
            return false;

        SourcePacketExtension audioSSRC = audioSSRCs.get(0);
        SourcePacketExtension videoSSRC = videoSSRCs.get(0);

        // Will merge stream by modifying msid and copying cname and label

        String videoStreamId = getStreamId(videoSSRC);
        String audioTrackId = getTrackId(audioSSRC);
        ParameterPacketExtension audioMsid = getParam(audioSSRC, "msid");

        if ( StringUtils.isNullOrEmpty(videoStreamId)
             || StringUtils.isNullOrEmpty(audioTrackId)
             ||  audioMsid == null )
        {
            return false;
        }

        // Copy cname and label
        copyParamAttr(audioSSRC, videoSSRC, "cname");
        copyParamAttr(audioSSRC, videoSSRC, "mslabel");

        audioMsid.setValue(videoStreamId + " " + audioTrackId);

        return true;
    }

    public static void setSSRCOwner(SourcePacketExtension ssrcPe, String owner)
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
