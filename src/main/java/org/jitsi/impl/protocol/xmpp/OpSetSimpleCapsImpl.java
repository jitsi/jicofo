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

import net.java.sip.communicator.util.*;

import org.jitsi.jicofo.discovery.*;
import org.jitsi.protocol.xmpp.*;

import org.jivesoftware.smack.*;

import java.util.*;

/**
 *
 */
public class OpSetSimpleCapsImpl
    implements OperationSetSimpleCaps
{
    /**
     * The logger.
     */
    private final static Logger logger
        = Logger.getLogger(OpSetSimpleCapsImpl.class);

    private final XmppProtocolProvider xmppProvider;

    public OpSetSimpleCapsImpl(XmppProtocolProvider xmppProtocolProvider)
    {
        this.xmppProvider = xmppProtocolProvider;
    }

    @Override
    public Set<String> getItems(String node)
    {
        try
        {
            return xmppProvider.discoverItems(node);
        }
        catch (XMPPException e)
        {
            logger.error(
                "Error while discovering the services of " + node
                        + " , error msg: " + e.getMessage());

            return null;
        }
    }

    @Override
    public boolean hasFeatureSupport(String node, String[] features)
    {
        List<String> itemFeatures = getFeatures(node);

        return itemFeatures != null &&
            DiscoveryUtil.checkFeatureSupport(features, itemFeatures);

    }
    
    public List<String> getFeatures(String node)
    {
        return xmppProvider.getEntityFeatures(node);
    }

    //@Override
    public boolean hasFeatureSupport(String node, String subnode,
                                     String[] features)
    {
        return xmppProvider.checkFeatureSupport(node, subnode, features);
    }
}
