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

import net.java.sip.communicator.impl.protocol.jabber.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jirecon.*;
import net.java.sip.communicator.util.*;

import org.jitsi.eventadmin.*;
import org.jitsi.jicofo.discovery.*;
import org.jitsi.jicofo.discovery.Version;
import org.jitsi.jicofo.event.*;
import org.jitsi.osgi.*;
import org.jitsi.protocol.xmpp.*;

import org.osgi.framework.*;

import java.util.*;

/**
 * Class manages discovered components discovery of Jitsi Meet application
 * services like bridge, recording, SIP gateway and so on...
 *
 * @author Pawel Domas
 */
public class JitsiMeetServices
    extends EventHandlerActivator
{
    /**
     * The logger
     */
    private final static Logger logger
        = Logger.getLogger(JitsiMeetServices.class);

    /**
     * Feature set advertised by videobridge.
     */
    public static final String[] VIDEOBRIDGE_FEATURES = new String[]
        {
            ColibriConferenceIQ.NAMESPACE,
            ProtocolProviderServiceJabberImpl
                .URN_XMPP_JINGLE_DTLS_SRTP,
            ProtocolProviderServiceJabberImpl
                .URN_XMPP_JINGLE_ICE_UDP_1,
            ProtocolProviderServiceJabberImpl
                .URN_XMPP_JINGLE_RAW_UDP_0
        };

    /**
     * Feature set advertised by videobridge which does support health-checks.
     */
    public static final String[] VIDEOBRIDGE_FEATURES2 = new String[]
        {
            ColibriConferenceIQ.NAMESPACE,
            DiscoveryUtil.FEATURE_HEALTH_CHECK,
            ProtocolProviderServiceJabberImpl
                .URN_XMPP_JINGLE_DTLS_SRTP,
            ProtocolProviderServiceJabberImpl
                .URN_XMPP_JINGLE_ICE_UDP_1,
            ProtocolProviderServiceJabberImpl
                .URN_XMPP_JINGLE_RAW_UDP_0
        };

    /**
     * The XMPP Service Discovery features of MUC service provided by the XMPP
     * server.
     */
    private static final String[] MUC_FEATURES
        = { "http://jabber.org/protocol/muc" };

    /**
     * Features advertised by Jirecon recorder container.
     */
    private static final String[] JIRECON_RECORDER_FEATURES = new String[]
        {
            JireconIqProvider.NAMESPACE
        };

    /**
     * Features advertised by SIP gateway component.
     */
    private static final String[] SIP_GW_FEATURES = new String[]
        {
            "http://jitsi.org/protocol/jigasi",
            "urn:xmpp:rayo:0"
        };

    /**
     * Features used to recognize pub-sub service.
     */
    /*private static final String[] PUBSUB_FEATURES = new String[]
        {
            "http://jabber.org/protocol/pubsub",
            "http://jabber.org/protocol/pubsub#subscribe"
        };*/

    /**
     * Manages Jitsi Videobridge component XMPP addresses.
     */
    private final BridgeSelector bridgeSelector;

    /**
     * The name of XMPP domain to which Jicofo user logs in.
     */
    private final String jicofoUserDomain;

    /**
     * Jirecon recorder component XMPP address.
     */
    private String jireconRecorder;

    /**
     * SIP gateway component XMPP address.
     */
    private String sipGateway;

    /**
     * The address of MUC component served by our XMPP domain.
     */
    private String mucService;

    /**
     * <tt>Version</tt> IQ instance holding detected XMPP server's version
     * (if any).
     */
    private Version XMPPServerVersion;

    /**
     * Returns <tt>true</tt> if given list of features complies with JVB feature
     * list.
     * @param features the list of feature to be checked.
     */
    static public boolean isJitsiVideobridge(List<String> features)
    {
        return DiscoveryUtil.checkFeatureSupport(
                VIDEOBRIDGE_FEATURES, features);
    }

    /**
     * Creates new instance of <tt>JitsiMeetServices</tt>
     *
     * @param jicofoUserDomain the name of the XMPP domain to which Jicofo user
     *        is connecting to.
     * @param operationSet subscription operation set to be used for watching
     *        JVB stats sent over pub-sub.
     */
    public JitsiMeetServices(String jicofoUserDomain,
                             OperationSetSubscription operationSet)
    {
        super(new String[] { BridgeEvent.HEALTH_CHECK_FAILED });

        this.jicofoUserDomain = jicofoUserDomain;
        this.bridgeSelector = new BridgeSelector(operationSet);
    }

    /**
     * Called by other classes when they detect JVB instance.
     * @param bridgeJid the JID of discovered JVB component.
     */
    void newBridgeDiscovered(String bridgeJid, Version version)
    {
        bridgeSelector.addJvbAddress(bridgeJid, version);
    }

    /**
     * Call when new component becomes available.
     *
     * @param node component XMPP address
     * @param features list of features supported by <tt>node</tt>
     * @param version the <tt>Version</tt> IQ which carries the info about
     *                <tt>node</tt> version(if any).
     */
    void newNodeDiscovered(String node, List<String> features, Version version)
    {
        if (isJitsiVideobridge(features))
        {
            newBridgeDiscovered(node, version);
        }
        else if (
            jireconRecorder == null
                && DiscoveryUtil.checkFeatureSupport(
                        JIRECON_RECORDER_FEATURES, features))
        {
            logger.info("Discovered Jirecon recorder: " + node);

            setJireconRecorder(node);
        }
        else if (sipGateway == null
            && DiscoveryUtil.checkFeatureSupport(SIP_GW_FEATURES, features))
        {
            logger.info("Discovered SIP gateway: " + node);

            setSipGateway(node);
        }
        else if (mucService == null
            && DiscoveryUtil.checkFeatureSupport(MUC_FEATURES, features))
        {
            logger.info("MUC component discovered: " + node);

            setMucService(node);
        }
        else if (jicofoUserDomain != null && jicofoUserDomain.equals(node))
        {
            this.XMPPServerVersion = version;

            logger.info("Detected XMPP server version: " + version);
        }
        /*
        FIXME: pub-sub service auto-detect ?
        else if (capsOpSet.hasFeatureSupport(item, PUBSUB_FEATURES))
        {
            // Potential PUBSUB service
            logger.info("Potential PUBSUB service:" + item);
            List<String> subItems = capsOpSet.getItems(item);
            for (String subItem: subItems)
            {
                logger.info("Subnode " + subItem + " of " + item);
                capsOpSet.hasFeatureSupport(
                    item, subItem, VIDEOBRIDGE_FEATURES);
            }
        }*/
    }

    /**
     * Call when components goes offline.
     *
     * @param node XMPP address of disconnected XMPP component.
     */
    void nodeNoLongerAvailable(String node)
    {
        if (bridgeSelector.isJvbOnTheList(node))
        {
            bridgeSelector.removeJvbAddress(node);
        }
        else if (node.equals(jireconRecorder))
        {
            logger.warn("Jirecon recorder went offline: " + node);

            jireconRecorder = null;
        }
        else if (node.equals(sipGateway))
        {
            logger.warn("SIP gateway went offline: " + node);

            sipGateway = null;
        }
        else if (node.equals(mucService))
        {
            logger.warn("MUC component went offline: " + node);

            mucService = null;
        }
    }

    /**
     * Sets new XMPP address of the SIP gateway component.
     * @param sipGateway the XMPP address to be set as SIP gateway component
     *                   address.
     */
    void setSipGateway(String sipGateway)
    {
        this.sipGateway = sipGateway;
    }

    /**
     * Returns XMPP address of SIP gateway component.
     */
    public String getSipGateway()
    {
        return sipGateway;
    }

    /**
     * Sets new XMPP address of the Jirecon jireconRecorder component.
     * @param jireconRecorder the XMPP address to be set as Jirecon recorder
     *                        component address.
     */
    public void setJireconRecorder(String jireconRecorder)
    {
        this.jireconRecorder = jireconRecorder;
    }

    /**
     * Returns the XMPP address of Jirecon recorder component.
     */
    public String getJireconRecorder()
    {
        return jireconRecorder;
    }

    /**
     * Returns {@link BridgeSelector} bound to this instance that can be used to
     * select the videobridge on the xmppDomain handled by this instance.
     */
    public BridgeSelector getBridgeSelector()
    {
        return bridgeSelector;
    }

    /**
     * Returns the address of MUC component for our XMPP domain.
     */
    public String getMucService()
    {
        return mucService;
    }

    /**
     * Sets the address of MUC component.
     * @param mucService component sub domain that refers to MUC
     */
    public void setMucService(String mucService)
    {
        this.mucService = mucService;
    }

    @Override
    public void start(BundleContext bundleContext)
        throws Exception
    {
        bridgeSelector.init();

        super.start(bundleContext);
    }

    @Override
    public void handleEvent(Event event)
    {
        if (BridgeEvent.HEALTH_CHECK_FAILED.equals(event.getTopic()))
        {
            BridgeEvent bridgeEvent = (BridgeEvent) event;

            bridgeSelector.removeJvbAddress(bridgeEvent.getBridgeJid());
        }
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
}
