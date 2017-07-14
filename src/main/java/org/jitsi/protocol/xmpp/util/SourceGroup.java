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

import org.jitsi.util.*;

import java.util.*;

/**
 * Wrapper for <tt>SourceGroupPacketExtension</tt>.
 *
 * @author Pawel Domas
 */
public class SourceGroup
{
    /**
     * Underlying source group packet extension.
     */
    private final SourceGroupPacketExtension group;

    /**
     * Extracts source groups from Jingle content packet extension.
     * @param content the <tt>ContentPacketExtension</tt> that contains(or not)
     *                the description of source groups.
     * @return the list of <tt>SourceGroup</tt>s described by given
     *         <tt>ContentPacketExtension</tt>.
     */
    public static List<SourceGroup> getSourceGroupsForContent(
            ContentPacketExtension content)
    {
        List<SourceGroup> groups = new ArrayList<SourceGroup>();

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
            groups.add(new SourceGroup(groupPe));
        }

        return groups;
    }

    /**
     * Creates new instance of <tt>SourceGroup</tt>.
     * @param group the packet extension that described source group to be wrapped
     *              by new object.
     * @throws NullPointerException if <tt>group</tt> is <tt>null</tt>.
     */
    public SourceGroup(SourceGroupPacketExtension group)
    {
        this.group = Objects.requireNonNull(group, "group");
    }

    /**
     * Adds source to this group.
     *
     * @param source the <tt>SourcePacketExtension</tt> to be added to this
     *               group.
     */
    public void addSource(SourcePacketExtension source)
    {
        group.addChildExtension(source);
    }

    /**
     * Adds the list of sources to this group.
     *
     * @param video the list of <tt>SourcePacketExtension</tt> which will be
     *              added to this group.
     */
    public void addSources(List<SourcePacketExtension> video)
    {
        group.addSources(video);
    }

    /**
     * Returns the sources contained in this group.
     * @return the internal list that stores <tt>SourcePacketExtension</tt>
     */
    public List<SourcePacketExtension> getSources()
    {
        return group.getSources();
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
     * this <tt>SourceGroup</tt> instance.
     */
    public SourceGroupPacketExtension getPacketExtension()
    {
        return group;
    }

    /**
     * Returns full copy of this <tt>SourceGroup</tt>.
     */
    public SourceGroup copy()
    {
        return new SourceGroup(getExtensionCopy());
    }

    @Override
    public boolean equals(Object obj)
    {
        if (!(obj instanceof SourceGroup))
        {
            return false;
        }

        SourceGroup other = (SourceGroup) obj;
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
        for (SourcePacketExtension sourceToFind : group.getSources())
        {
            boolean found = false;
            for (SourcePacketExtension source : other.group.getSources())
            {
                if (source.sourceEquals(sourceToFind))
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
     * Check if this <tt>SourceGroup</tt> contains any
     * <tt>SourceGroupPacketExtension</tt>s.
     *
     * @return <tt>true</tt> if this <tt>SourceGroup</tt> is empty or
     *         <tt>false</tt> otherwise.
     */
    public boolean isEmpty()
    {
        return this.group.getSources().isEmpty();
    }

    @Override
    public String toString()
    {
        StringBuilder sources = new StringBuilder();
        for (SourcePacketExtension source : this.group.getSources())
        {
            // FIXME do not print for the last element
            sources.append(source.toString()).append(", ");
        }
        return "SourceGroup[" + this.group.getSemantics() + ", " + sources
            + "]@" + Integer.toHexString(hashCode());
    }

    public String getSemantics()
    {
        return group.getSemantics();
    }
}
