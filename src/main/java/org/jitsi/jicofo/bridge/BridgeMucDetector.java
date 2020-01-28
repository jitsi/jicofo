/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2018 Atlassian Pty Ltd
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
package org.jitsi.jicofo.bridge;

import org.jitsi.jicofo.*;
import org.jitsi.xmpp.extensions.colibri.*;
import net.java.sip.communicator.util.*;
import org.jitsi.jicofo.xmpp.*;
import org.jxmpp.jid.*;

/**
 * Detects jitsi-videobridge instances through a MUC.
 *
 * @author Boris Grozev
 */
public class BridgeMucDetector
    extends BaseBrewery<ColibriStatsExtension>
{
    /**
     * The logger used by the {@link BridgeMucDetector} class and its instances.
     */
    private static final Logger logger = Logger.getLogger(BaseBrewery.class);

    /**
     * The name of the property used to configure the full JID of the MUC to
     * use for detection of jitsi-videobridge instances.
     */
    public static final String BRIDGE_MUC_PNAME
        = "org.jitsi.jicofo.BRIDGE_MUC";

    /**
     * The {@link BridgeSelector} instance which will be notified when new
     * jitsi-videobridge instances are detected, or when they update their
     * status.
     */
    private final BridgeSelector bridgeSelector;

    /**
     * Initializes a new {@link BridgeMucDetector} instance.
     *
     * @param protocolProvider the {@link ProtocolProviderHandler} instance
     * to which this {@link BridgeMucDetector} will attach.
     * @param breweryJid the MUC JID of the room which this detector will join.
     * @param bridgeSelector the {@link BridgeSelector} instance which will be
     * notified when new jitsi-videobridge instances are detected, or when they
     * update their status.
     */
    public BridgeMucDetector(
        ProtocolProviderHandler protocolProvider,
        String breweryJid,
        BridgeSelector bridgeSelector)
    {
        super(protocolProvider,
              breweryJid,
              ColibriStatsExtension.ELEMENT_NAME,
              ColibriStatsExtension.NAMESPACE);

        this.bridgeSelector = bridgeSelector;
    }

    /**
     * {@inheritDoc}
     * @param jid the brewing instance muc address
     * @param stats
     */
    @Override
    protected void onInstanceStatusChanged(
        Jid jid,
        ColibriStatsExtension stats)
    {
        if (logger.isDebugEnabled())
        {
            logger.debug(
                "Received updated status for " + jid + ": " + stats.toXML());
        }

        bridgeSelector.addJvbAddress(jid).setStats(stats);
    }

    @Override
    protected void notifyInstanceOffline(Jid jid)
    {
        logger.info("A bridge left the MUC: " + jid);
        bridgeSelector.removeJvbAddress(jid);
    }
}

