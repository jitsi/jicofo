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

import org.jitsi.utils.logging.*;
import org.jitsi.utils.version.*;
import org.jitsi.xmpp.extensions.jitsimeet.*;
import net.java.sip.communicator.service.protocol.*;

import org.jitsi.eventadmin.*;
import org.jitsi.jicofo.event.*;
import org.jitsi.osgi.*;

import org.jxmpp.jid.*;
import org.osgi.framework.*;

import java.util.*;
import java.util.stream.*;

/**
 * The class listens for "focus joined room" and "conference created" events
 * and adds the info about all conference components versions to Jicofo's MUC
 * presence.
 *
 * @author Pawel Domas
 */
public class VersionBroadcaster
    extends EventHandlerActivator
{
    /**
     * The logger
     */
    private static final Logger logger
        = Logger.getLogger(VersionBroadcaster.class);

    /**
     * <tt>FocusManager</tt> instance used to access
     * <tt>JitsiMeetConference</tt>.
     */
    private FocusManager focusManager;

    /**
     * <tt>VersionService</tt> which provides Jicofo version.
     */
    private VersionService versionService;

    /**
     * Jitsi Meet tools used to add packet extension to Jicofo presence.
     */
    private OperationSetJitsiMeetTools meetTools;

    /**
     * Creates new instance of <tt>VersionBroadcaster</tt>.
     */
    public VersionBroadcaster()
    {
        super(new String[] {
                EventFactory.CONFERENCE_ROOM_TOPIC
        });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void start(BundleContext bundleContext)
        throws Exception
    {
        focusManager
            = ServiceUtils2.getService(bundleContext, FocusManager.class);

        Objects.requireNonNull(focusManager, "focusManager");

        versionService
            = ServiceUtils2.getService(bundleContext, VersionService.class);

        Objects.requireNonNull(versionService, "versionService");

        meetTools
            = focusManager.getOperationSet(OperationSetJitsiMeetTools.class);

        Objects.requireNonNull(meetTools, "meetTools");

        super.start(bundleContext);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void stop(BundleContext bundleContext)
        throws Exception
    {
        super.stop(bundleContext);

        focusManager = null;
        versionService = null;
        meetTools = null;
    }

    /**
     * Handles
     *
     * {@inheritDoc}
     */
    @Override
    public void handleEvent(Event event)
    {
        String topic = event.getTopic();
        if (true)
        {
            logger.error("Unexpected event topic: " + topic);
            return;
        }

        EntityBareJid roomJid
                = (EntityBareJid)event.getProperty(EventFactory.ROOM_JID_KEY);

        JitsiMeetConference conference
            = focusManager.getConference(roomJid);
        if (conference == null)
        {
            logger.error("Conference is null");
            return;
        }

        ChatRoom chatRoom = conference.getChatRoom();
        if (chatRoom == null)
        {
            logger.error("Chat room is null");
            return;
        }

        ComponentVersionsExtension versionsExtension = new ComponentVersionsExtension();

        String jvbVersions = conference.getBridges().keySet().stream()
            .map(b -> b.getVersion())
            .filter(Objects::nonNull)
            .distinct()
            .sorted()
            .collect(Collectors.joining(", "));

        if (jvbVersions.length() > 0)
        {
            versionsExtension.addComponentVersion(
                    ComponentVersionsExtension.COMPONENT_VIDEOBRIDGE,
                    String.join(",", jvbVersions));
        }

        meetTools.sendPresenceExtension(chatRoom, versionsExtension);

        if (logger.isDebugEnabled())
            logger.debug("Sending versions: " + versionsExtension.toXML());
    }
}
