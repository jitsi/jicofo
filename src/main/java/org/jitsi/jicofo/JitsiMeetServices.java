/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2018 - present 8x8, Inc.
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

import org.jitsi.jicofo.bridge.*;

import org.jitsi.jicofo.jibri.JibriConfig;
import org.jitsi.jicofo.jigasi.*;
import org.jitsi.jicofo.recording.jibri.*;
import org.jitsi.utils.logging.*;

import org.json.simple.*;

import java.util.*;

/**
 * Class manages discovery of Jitsi Meet application services like
 * jitsi-videobridge, recording, SIP gateway and so on...
 *
 * @author Pawel Domas
 */
public class JitsiMeetServices
{
    /**
     * The logger
     */
    private final static Logger logger = Logger.getLogger(JitsiMeetServices.class);

    /**
     * Manages Jitsi Videobridge component XMPP addresses.
     */
    private final BridgeSelector bridgeSelector;

    /**
     * The {@link ProtocolProviderHandler} for JVB XMPP connection.
     */
    private final ProtocolProviderHandler jvbBreweryProtocolProvider;

    /**
     * The {@link ProtocolProviderHandler} for Jicofo XMPP connection.
     */
    private final ProtocolProviderHandler protocolProvider;

    private JibriDetector jibriDetector;

    private JigasiDetector jigasiDetector;

    private JibriDetector sipJibriDetector;

    private BridgeMucDetector bridgeMucDetector;

    /**
     * Creates new instance of <tt>JitsiMeetServices</tt>
     *  @param protocolProviderHandler {@link ProtocolProviderHandler} for Jicofo XMPP connection.
     * @param jvbMucProtocolProvider {@link ProtocolProviderHandler} for JVB XMPP connection.
     */
    public JitsiMeetServices(ProtocolProviderHandler protocolProviderHandler,
                             ProtocolProviderHandler jvbMucProtocolProvider)
    {
        Objects.requireNonNull(protocolProviderHandler, "protocolProviderHandler");
        Objects.requireNonNull(jvbMucProtocolProvider, "jvbMucProtocolProvider");

        this.protocolProvider = protocolProviderHandler;
        this.jvbBreweryProtocolProvider = jvbMucProtocolProvider;
        this.bridgeSelector = new BridgeSelector();
    }

    /**
     * Returns Jibri SIP detector if available.
     * @return {@link JibriDetector} or <tt>null</tt> if not configured.
     */
    public JibriDetector getSipJibriDetector()
    {
        return sipJibriDetector;
    }

    /**
     * Returns {@link JibriDetector} instance that manages Jibri pool used by
     * this Jicofo process or <tt>null</tt> if unavailable in the current
     * session.
     */
    public JibriDetector getJibriDetector()
    {
        return jibriDetector;
    }

    /**
     * Returns {@link JigasiDetector} instance that manages Jigasi pool used by
     * this Jicofo process or <tt>null</tt> if unavailable in the current
     * session.
     */
    public JigasiDetector getJigasiDetector()
    {
        return jigasiDetector;
    }

    /**
     * Returns {@link BridgeSelector} bound to this instance that can be used to
     * select the videobridge on the xmppDomain handled by this instance.
     */
    public BridgeSelector getBridgeSelector()
    {
        return bridgeSelector;
    }

    public void start()
    {
        bridgeSelector.init();

        if (JibriConfig.config.breweryEnabled())
        {
            jibriDetector = new JibriDetector(protocolProvider, JibriConfig.config.getBreweryJid(), false);
            logger.info("Using a Jibri detector with MUC: " + JibriConfig.config.getBreweryJid());

            jibriDetector.init();
        }

        if (JigasiConfig.config.breweryEnabled())
        {
            jigasiDetector = new JigasiDetector(protocolProvider, JigasiConfig.config.getBreweryJid());
            logger.info("Using a Jigasi detector with MUC: " + JigasiConfig.config.getBreweryJid());

            jigasiDetector.init();
        }

        if (JibriConfig.config.sipBreweryEnabled())
        {
            sipJibriDetector = new JibriDetector(protocolProvider, JibriConfig.config.getSipBreweryJid(), true);
            logger.info("Using a SIP Jibri detector with MUC: " + JibriConfig.config.getSipBreweryJid());

            sipJibriDetector.init();
        }

        if (BridgeConfig.config.breweryEnabled())
        {
            bridgeMucDetector = new BridgeMucDetector(jvbBreweryProtocolProvider, bridgeSelector);
            bridgeMucDetector.init();
        }
    }

    public void stop()
    {
        if (jibriDetector != null)
        {
            jibriDetector.dispose();
            jibriDetector = null;
        }
        if (jigasiDetector != null)
        {
            jigasiDetector.dispose();
            jigasiDetector = null;
        }
        if (sipJibriDetector != null)
        {
            sipJibriDetector.dispose();
            sipJibriDetector = null;
        }
        if (bridgeMucDetector != null)
        {
            bridgeMucDetector.dispose();
            bridgeMucDetector = null;
        }

        bridgeSelector.dispose();
    }

    @SuppressWarnings("unchecked")
    public JSONObject getStats()
    {
        JSONObject json = new JSONObject();

        json.put("bridge_selector", bridgeSelector.getStats());
        JigasiDetector jigasiDetector = getJigasiDetector();
        if (jigasiDetector != null)
        {
            json.put("jigasi_detector", jigasiDetector.getStats());
        }

        // TODO: remove once we migrate to the new names (see FocusManager.getStats() which puts the same stats under
        // the 'jibri' key.
        JibriDetector jibriDetector = getJibriDetector();
        if (jibriDetector != null)
        {
            json.put("jibri_detector", jibriDetector.getStats());
        }

        // TODO: remove once we migrate to the new names (see FocusManager.getStats() which puts the same stats under
        // the 'jibri' key.
        JibriDetector sipJibriDetector = getSipJibriDetector();
        if (sipJibriDetector != null)
        {
            json.put("sip_jibri_detector", sipJibriDetector.getStats());
        }

        return json;
    }
}
