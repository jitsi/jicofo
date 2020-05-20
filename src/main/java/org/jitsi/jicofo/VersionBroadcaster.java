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
import org.jitsi.jicofo.discovery.Version;
import org.jitsi.jicofo.event.*;
import org.jitsi.osgi.*;

import org.jxmpp.jid.*;
import org.osgi.framework.*;

import java.util.*;
import java.util.stream.*;

/**
 * Providers info about all conference components versions to Jicofo's MUC
 * presence.
 *
 * @author Pawel Domas
 * @author Damian Minkov
 */
public class VersionBroadcaster
{
    /**
     * The logger
     */
    private static final Logger logger
        = Logger.getLogger(VersionBroadcaster.class);

    /**
     * Constructs versions extension to be sent with presence.
     * {@inheritDoc}
     */
    static ComponentVersionsExtension getVersionsExtension(
        JitsiMeetConference conference)
    {
        FocusManager focusManager = ServiceUtils2.getService(
            FocusBundleActivator.bundleContext, FocusManager.class);
        VersionService versionService = ServiceUtils2.getService(
            FocusBundleActivator.bundleContext, VersionService.class);

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
        org.jitsi.utils.version.Version jicofoVersion
            = versionService.getCurrentVersion();
        versionsExtension.addComponentVersion(
                ComponentVersionsExtension.COMPONENT_FOCUS,
                jicofoVersion.getApplicationName()
                    + "(" + jicofoVersion.toString() + ","
                    + System.getProperty("os.name") + ")");

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

        if (logger.isDebugEnabled())
            logger.debug("Providing versions: " + versionsExtension.toXML());

        return versionsExtension;
    }
}
