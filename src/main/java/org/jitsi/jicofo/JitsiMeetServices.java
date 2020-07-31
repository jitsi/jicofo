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

import org.jitsi.jicofo.discovery.*;
import org.jitsi.jicofo.discovery.Version;
import org.jitsi.jicofo.jigasi.*;
import org.jitsi.jicofo.recording.jibri.*;
import org.jitsi.jicofo.xmpp.*;
import org.jitsi.service.configuration.*;
import org.jitsi.utils.logging.*;

import org.json.simple.*;
import org.jxmpp.jid.*;

import java.util.*;

import static org.apache.commons.lang3.StringUtils.*;

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
    private final static Logger logger
        = Logger.getLogger(JitsiMeetServices.class);

    /**
     * Features advertised by SIP gateway component.
     */
    private static final String[] SIP_GW_FEATURES = new String[]
        {
            "http://jitsi.org/protocol/jigasi",
            "urn:xmpp:rayo:0"
        };

    /**
     * Manages Jitsi Videobridge component XMPP addresses.
     */
    private final BridgeSelector bridgeSelector;

    private final Set<BaseBrewery> breweryDetectors = new HashSet<>();

    /**
     * The name of XMPP domain to which Jicofo user logs in.
     */
    private final DomainBareJid jicofoUserDomain;

    /**
     * The {@link ProtocolProviderHandler} for JVB XMPP connection.
     */
    private final ProtocolProviderHandler jvbBreweryProtocolProvider;

    /**
     * The {@link ProtocolProviderHandler} for Jicofo XMPP connection.
     */
    private final ProtocolProviderHandler protocolProvider;

    /**
     * SIP gateway component XMPP address.
     */
    private Jid sipGateway;

    /**
     * <tt>Version</tt> IQ instance holding detected XMPP server's version
     * (if any).
     */
    private Version XMPPServerVersion;

    /**
     * Creates new instance of <tt>JitsiMeetServices</tt>
     *  @param protocolProviderHandler {@link ProtocolProviderHandler} for Jicofo
     *        XMPP connection.
     * @param jvbMucProtocolProvider {@link ProtocolProviderHandler} for JVB
     *        XMPP connection.
     * @param jicofoUserDomain the name of the XMPP domain to which Jicofo user
     */
    public JitsiMeetServices(ProtocolProviderHandler protocolProviderHandler,
                             ProtocolProviderHandler jvbMucProtocolProvider,
                             DomainBareJid jicofoUserDomain)
    {
        Objects.requireNonNull(
            protocolProviderHandler, "protocolProviderHandler");
        Objects.requireNonNull(
            jvbMucProtocolProvider, "jvbMucProtocolProvider");

        this.jicofoUserDomain = jicofoUserDomain;
        this.protocolProvider = protocolProviderHandler;
        this.jvbBreweryProtocolProvider = jvbMucProtocolProvider;
        this.bridgeSelector = new BridgeSelector();
    }

    /**
     * Call when new component becomes available.
     *
     * @param node component XMPP address
     * @param features list of features supported by <tt>node</tt>
     * @param version the <tt>Version</tt> IQ which carries the info about
     *                <tt>node</tt> version(if any).
     */
    void newNodeDiscovered(Jid node,
                           List<String> features,
                           Version version)
    {
        if (sipGateway == null
            && DiscoveryUtil.checkFeatureSupport(SIP_GW_FEATURES, features))
        {
            logger.info("Discovered SIP gateway: " + node);

            setSipGateway(node);
        }
        else if (jicofoUserDomain != null
                && jicofoUserDomain.equals(node) && version != null)
        {
            this.XMPPServerVersion = version;

            logger.info("Detected XMPP server version: "
                + version.getNameVersionOsString());
        }
    }

    /**
     * Call when a component goes offline.
     *
     * @param node XMPP address of disconnected XMPP component.
     */
    void nodeNoLongerAvailable(Jid node)
    {
        if (bridgeSelector.isJvbOnTheList(node))
        {
            bridgeSelector.removeJvbAddress(node);
        }
        else if (node.equals(sipGateway))
        {
            logger.warn("SIP gateway went offline: " + node);

            sipGateway = null;
        }
    }

    /**
     * Sets new XMPP address of the SIP gateway component.
     * @param sipGateway the XMPP address to be set as SIP gateway component
     *                   address.
     */
    void setSipGateway(Jid sipGateway)
    {
        this.sipGateway = sipGateway;
    }

    /**
     * Returns XMPP address of SIP gateway component.
     */
    public Jid getSipGateway()
    {
        return sipGateway;
    }

    /**
     * Returns Jibri SIP detector if available.
     * @return {@link JibriDetector} or <tt>null</tt> if not configured.
     */
    public JibriDetector getSipJibriDetector()
    {
        return breweryDetectors.stream()
            .filter(d -> d instanceof JibriDetector)
            .map(d -> ((JibriDetector) d))
            .filter(JibriDetector::isSip)
            .findFirst().orElse(null);
    }

    /**
     * Returns {@link JibriDetector} instance that manages Jibri pool used by
     * this Jicofo process or <tt>null</tt> if unavailable in the current
     * session.
     */
    public JibriDetector getJibriDetector()
    {
        return breweryDetectors.stream()
            .filter(d -> d instanceof JibriDetector)
            .map(d -> ((JibriDetector) d))
            .filter(d -> !d.isSip())
            .findFirst().orElse(null);
    }

    /**
     * Returns {@link JigasiDetector} instance that manages Jigasi pool used by
     * this Jicofo process or <tt>null</tt> if unavailable in the current
     * session.
     */
    public JigasiDetector getJigasiDetector()
    {
        return breweryDetectors.stream()
            .filter(d -> d instanceof JigasiDetector)
            .map(d -> ((JigasiDetector) d))
            .findFirst().orElse(null);
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

        ConfigurationService config = FocusBundleActivator.getConfigService();
        String jibriBreweryName
            = config.getString(JibriDetector.JIBRI_ROOM_PNAME);

        if (isNotBlank(jibriBreweryName))
        {
            JibriDetector jibriDetector
                = new JibriDetector(protocolProvider, jibriBreweryName, false);
            logger.info("Using a Jibri detector with MUC: " + jibriBreweryName);

            jibriDetector.init();
            breweryDetectors.add(jibriDetector);
        }

        String jigasiBreweryName
            = config.getString(JigasiDetector.JIGASI_ROOM_PNAME);
        if (isNotBlank(jigasiBreweryName))
        {
            JigasiDetector jigasiDetector = new JigasiDetector(protocolProvider, jigasiBreweryName);
            logger.info("Using a Jigasi detector with MUC: " + jigasiBreweryName);

            jigasiDetector.init();
            breweryDetectors.add(jigasiDetector);
        }

        String jibriSipBreweryName
            = config.getString(JibriDetector.JIBRI_SIP_ROOM_PNAME);
        if (isNotBlank(jibriSipBreweryName))
        {
            JibriDetector sipJibriDetector
                = new JibriDetector(
                        protocolProvider, jibriSipBreweryName, true);
            logger.info(
                "Using a SIP Jibri detector with MUC: " + jibriSipBreweryName);

            sipJibriDetector.init();
            breweryDetectors.add(sipJibriDetector);
        }

        String bridgeBreweryName
            = config.getString(BridgeMucDetector.BRIDGE_MUC_PNAME);
        if (isNotBlank(bridgeBreweryName))
        {
            BridgeMucDetector bridgeMucDetector
                = new BridgeMucDetector(
                    jvbBreweryProtocolProvider,
                    bridgeBreweryName,
                    bridgeSelector);
            logger.info(
                "Using a Bridge MUC detector with MUC: " + bridgeBreweryName);

            bridgeMucDetector.init();
            breweryDetectors.add(bridgeMucDetector);
        }
    }

    public void stop()
    {
        breweryDetectors.forEach(BaseBrewery::dispose);
        breweryDetectors.clear();

        bridgeSelector.dispose();
    }

    /**
     * The version of XMPP server to which Jicofo user is connecting to.
     *
     * @return {@link Version} instance which holds the version details. Can be
     *         <tt>null</tt> if not discovered yet.
     */
    public Version getXMPPServerVersion()
    {
        return XMPPServerVersion;
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

        JibriDetector jibriDetector = getJibriDetector();
        if (jibriDetector != null)
        {
            json.put("jibri_detector", jibriDetector.getStats());
        }

        JibriDetector sipJibriDetector = getSipJibriDetector();
        if (sipJibriDetector != null)
        {
            json.put("sip_jibri_detector", sipJibriDetector.getStats());
        }

        return json;
    }
}
