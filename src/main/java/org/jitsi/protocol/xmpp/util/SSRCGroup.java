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
import net.java.sip.communicator.impl.protocol.jabber.jinglesdp.*;

import org.jitsi.assertions.*;
import org.jitsi.util.*;

import java.util.*;

/**
 * Wrapper for <tt>SourceGroupPacketExtension</tt>.
 *
 * @author Pawel Domas
 */
public class SSRCGroup
{
    /**
     * Underlying source group packet extension.
     */
    private final SourceGroupPacketExtension group;

    /**
     * Extracts SSRC groups from Jingle content packet extension.
     * @param content the <tt>ContentPacketExtension</tt> that contains(or not)
     *                the description of SSRC groups.
     * @return the list of <tt>SSRCGroup</tt>s described by given
     *         <tt>ContentPacketExtension</tt>.
     */
    public static List<SSRCGroup> getSSRCGroupsForContent(
            ContentPacketExtension content)
    {
        List<SSRCGroup> groups = new ArrayList<SSRCGroup>();

        RtpDescriptionPacketExtension rtpDescPe
            = JingleUtils.getRtpDescription(content);

        if (rtpDescPe == null)
        {
            return groups;
        }

        List<SourceGroupPacketExtension> groupExtensions
            = rtpDescPe.getChildExtensionsOfType(
                    SourceGroupPacketExtension.class);

        for (SourceGroupPacketExtension groupPe : groupExtensions)
        {
            groups.add(new SSRCGroup(groupPe));
        }

        return groups;
    }

    /**
     * Creates new instance of <tt>SSRCGroup</tt>.
     * @param group the packet extension that described SSRC group to be wrapped
     *              by new object.
     * @throws NullPointerException if <tt>group</tt> is <tt>null</tt>.
     */
    public SSRCGroup(SourceGroupPacketExtension group)
    {
        Assert.notNull(group, "group");

        this.group = group;
    }

    /**
     * Adds SSRC to this group.
     *
     * @param ssrcPe the <tt>SourcePacketExtension</tt> to be added to this
     *               group.
     */
    public void addSource(SourcePacketExtension ssrcPe)
    {
        group.addChildExtension(ssrcPe);
    }

    /**
     * Adds the list of SSRCs to this group.
     *
     * @param video the list of <tt>SourcePacketExtension</tt> which will be
     *              added to this group.
     */
    public void addSources(List<SourcePacketExtension> video)
    {
        group.addSources(video);
    }

    /**
     * Returns deep copy of underlying <tt>SourceGroupPacketExtension</tt>.
     */
    public SourceGroupPacketExtension getExtensionCopy()
    {
        return group.copy();
    }

    /**
     * Returns the underlying <tt>SourceGroupPacketExtension</tt> wrapped by
     * this <tt>SSRCGroup</tt> instance.
     */
    public SourceGroupPacketExtension getPacketExtension()
    {
        return group;
    }

    /**
     * Returns full copy of this <tt>SSRCGroup</tt>.
     */
    public SSRCGroup copy()
    {
        return new SSRCGroup(getExtensionCopy());
    }

    @Override
    public boolean equals(Object obj)
    {
        if (!(obj instanceof SSRCGroup))
        {
            return false;
        }

        SSRCGroup other = (SSRCGroup) obj;
        String semantics = other.group.getSemantics();
        if (StringUtils.isNullOrEmpty(semantics)
            && !StringUtils.isNullOrEmpty(group.getSemantics()))
        {
            return false;
        }
        if (!group.getSemantics().equals(semantics))
        {
            return false;
        }
        for (SourcePacketExtension ssrcToFind : group.getSources())
        {
            boolean found = false;
            for (SourcePacketExtension ssrc : other.group.getSources())
            {
                if (ssrc.getSSRC() == ssrcToFind.getSSRC())
                {
                    found = true;
                    break;
                }
            }
            if (!found)
            {
                return false;
            }
        }
        return true;
    }

    /**
     * Check if this <tt>SSRCGroup</tt> contains any
     * <tt>SourceGroupPacketExtension</tt>s.
     *
     * @return <tt>true</tt> if this <tt>SSRCGroup</tt> is empty or
     *         <tt>false</tt> otherwise.
     */
    public boolean isEmpty()
    {
        return this.group.getSources().isEmpty();
    }
}
