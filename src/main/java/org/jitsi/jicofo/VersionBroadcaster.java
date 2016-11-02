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

import net.java.sip.communicator.impl.protocol.jabber.extensions.jitsimeet.*;
import net.java.sip.communicator.service.protocol.*;
import net.java.sip.communicator.util.*;

import org.jitsi.assertions.*;
import org.jitsi.eventadmin.*;
import org.jitsi.jicofo.discovery.Version;
import org.jitsi.jicofo.log.*;
import org.jitsi.osgi.*;
import org.jitsi.service.version.*;

import org.osgi.framework.*;

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
                EventFactory.FOCUS_JOINED_ROOM_TOPIC,
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
            = ServiceUtils.getService(bundleContext, FocusManager.class);

        Assert.notNull(focusManager, "focusManager");

        versionService
            = ServiceUtils.getService(bundleContext, VersionService.class);

        Assert.notNull(versionService, "versionService");

        meetTools
            = focusManager.getOperationSet(OperationSetJitsiMeetTools.class);

        Assert.notNull(meetTools, "meetTools");

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
     * Handles {@link EventFactory#FOCUS_JOINED_ROOM_TOPIC} and
     * {@link EventFactory#CONFERENCE_ROOM_TOPIC}.
     *
     * {@inheritDoc}
     */
    @Override
    public void handleEvent(Event event)
    {
        String topic = event.getTopic();
        if (!topic.equals(EventFactory.FOCUS_JOINED_ROOM_TOPIC)
            && !topic.equals(EventFactory.CONFERENCE_ROOM_TOPIC))
        {
            logger.error("Unexpected event topic: " + topic);
            return;
        }

        String roomJid = (String) event.getProperty(EventFactory.ROOM_JID_KEY);

        JitsiMeetConference conference = focusManager.getConference(roomJid);
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

        JitsiMeetServices meetServices = focusManager.getJitsiMeetServices();
        ComponentVersionsExtension versionsExtension
            = new ComponentVersionsExtension();

        // XMPP
        Version xmppServerVersion = meetServices.getXMPPServerVersion();
        if (xmppServerVersion != null)
        {
            versionsExtension.addComponentVersion(
                   ComponentVersionsExtension.COMPONENT_XMPP_SERVER,
                    xmppServerVersion.getNameVersionOsString());
        }

        // Conference focus
        org.jitsi.service.version.Version jicofoVersion
            = versionService.getCurrentVersion();
        versionsExtension.addComponentVersion(
                ComponentVersionsExtension.COMPONENT_FOCUS,
                jicofoVersion.getApplicationName()
                    + "(" + jicofoVersion.toString() + ","
                    + System.getProperty("os.name") + ")");

        // Videobridge
        // It is not be reported for FOCUS_JOINED_ROOM_TOPIC
        String bridgeJid
            = (String) event.getProperty(EventFactory.BRIDGE_JID_KEY);
        Version jvbVersion
            = bridgeJid == null
                ? null : meetServices.getBridgeVersion(bridgeJid);
        if (jvbVersion != null)
        {
            versionsExtension.addComponentVersion(
                    ComponentVersionsExtension.COMPONENT_VIDEOBRIDGE,
                    jvbVersion.getNameVersionOsString());
        }

        meetTools.sendPresenceExtension(chatRoom, versionsExtension);

        if (logger.isDebugEnabled())
            logger.debug("Sending versions: " + versionsExtension.toXML());
    }
}
