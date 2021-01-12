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
package org.jitsi.impl.protocol.xmpp;

import org.jitsi.protocol.xmpp.*;
import org.jitsi.utils.logging.*;
import org.jivesoftware.smackx.disco.*;
import org.jivesoftware.smackx.disco.packet.*;
import org.jxmpp.jid.*;

import java.util.*;
import java.util.stream.*;

/**
 *
 */
public class OpSetSimpleCapsImpl
    implements OperationSetSimpleCaps
{
    /**
     * The logger.
     */
    private final static Logger logger = Logger.getLogger(OpSetSimpleCapsImpl.class);

    private ServiceDiscoveryManager discoveryManager;

    private final XmppProtocolProvider xmppProvider;

    public OpSetSimpleCapsImpl(XmppProtocolProvider xmppProvider)
    {
        this.xmppProvider = xmppProvider;
    }

    private ServiceDiscoveryManager getDiscoveryManager() {
        if (this.discoveryManager == null)
        {
            this.discoveryManager = ServiceDiscoveryManager.getInstanceFor(xmppProvider.getConnection());
        }

        return discoveryManager;
    }

    public List<String> getFeatures(Jid node)
    {
        if (getDiscoveryManager() == null)
        {
            return null;
        }

        try
        {
            DiscoverInfo info = discoveryManager.discoverInfo(node);
            if (info != null)
            {
                return info.getFeatures()
                        .stream()
                        .map(DiscoverInfo.Feature::getVar)
                        .collect(Collectors.toList());
            }
        }
        catch (Exception e)
        {
            logger.warn(
                    String.format(
                            "Failed to discover features for %s: %s", node, e.getMessage()));
        }

        return null;
    }
}
