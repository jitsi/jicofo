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
package org.jitsi.jicofo.recording.jibri;

import net.java.sip.communicator.impl.protocol.jabber.extensions.jibri.*;
import net.java.sip.communicator.util.*;

import org.jitsi.eventadmin.*;
import org.jitsi.jicofo.*;
import org.jitsi.jicofo.xmpp.*;
import org.jitsi.osgi.*;
import org.jxmpp.jid.*;

/**
 * <tt>JibriDetector</tt> manages the pool of Jibri instances which exist in
 * the current session. Does that by joining "brewery" room where Jibris connect
 * to and publish their's status in MUC presence. It emits {@link JibriEvent}s
 * to reflect current Jibri's status.
 *
 * @author Pawel Domas
 */
public class JibriDetector
    extends BaseBrewery<JibriStatusPacketExt>
{
    /**
     * The logger
     */
    private static final Logger logger = Logger.getLogger(JibriDetector.class);

    /**
     * The name of config property which provides the name of the MUC room in
     * which all Jibri instances report their availability status.
     * Can be just roomName, then the muc service will be discovered from server
     * and in case of multiple will use the first one.
     * Or it can be full room id: roomName@muc-servicename.jabserver.com.
     */
    public static final String JIBRI_ROOM_PNAME
        = "org.jitsi.jicofo.jibri.BREWERY";

    /**
     * The name of config property which provides the name of the MUC room in
     * which all SIP Jibri instances report their availability status.
     * Can be just roomName, then the muc service will be discovered from server
     * and in case of multiple will use the first one.
     * Or it can be full room id: roomName@muc-servicename.jabserver.com.
     */
    public static final String JIBRI_SIP_ROOM_PNAME
        = "org.jitsi.jicofo.jibri.SIP_BREWERY";

    /**
     * The reference to the <tt>EventAdmin</tt> service which is used to send
     * {@link JibriEvent}s.
     */
    private final OSGIServiceRef<EventAdmin> eventAdminRef;

    /**
     * Indicates whether this instance detects SIP gateway Jibris or regular
     * live streaming Jibris.
     */
    private final boolean isSIP;

    /**
     * Creates new instance of <tt>JibriDetector</tt>
     * @param protocolProvider the instance fo <tt>ProtocolProviderHandler</tt>
     *        for Jicofo's XMPP connection.
     * @param jibriBreweryName the name of the Jibri brewery MUC room where all
     *        Jibris will gather.
     * @param isSIP <tt>true</tt> if this instance will work with SIP gateway
     *        Jibris or <tt>false</tt> for live streaming Jibris
     */
    public JibriDetector(ProtocolProviderHandler protocolProvider,
                         String jibriBreweryName,
                         boolean isSIP)
    {
        super(
            protocolProvider,
            jibriBreweryName,
            JibriStatusPacketExt.ELEMENT_NAME,
            JibriStatusPacketExt.NAMESPACE);

        this.eventAdminRef
            = new OSGIServiceRef<>(
                    FocusBundleActivator.bundleContext, EventAdmin.class);
        this.isSIP = isSIP;
    }

    private String getLogName()
    {
        return isSIP ? "SIP Jibri" : "Jibri";
    }

    /**
     * Selects first idle Jibri which can be used to start recording.
     *
     * @return XMPP address of idle Jibri instance or <tt>null</tt> if there are
     * no Jibris available currently.
     */
    public EntityFullJid selectJibri()
    {
        return instances.stream()
            .filter(
                jibri -> JibriStatusPacketExt.Status.IDLE.equals(
                    jibri.status.getStatus()))
            .map(jibri -> jibri.jid)
            .findFirst()
            .orElse(null);
    }

    @Override
    protected void onInstanceStatusChanged(
        EntityFullJid jid,
        JibriStatusPacketExt presenceExt)
    {
        JibriStatusPacketExt.Status status = presenceExt.getStatus();

        if (JibriStatusPacketExt.Status.UNDEFINED.equals(status))
        {
            notifyInstanceOffline(jid);
        }
        else if (JibriStatusPacketExt.Status.IDLE.equals(status))
        {
            notifyJibriStatus(jid, true);
        }
        else if (JibriStatusPacketExt.Status.BUSY.equals(status))
        {
            notifyJibriStatus(jid, false);
        }
        else
        {
            logger.error("Unknown Jibri status: " + status + " for " + jid);
        }
    }

    @Override
    protected void notifyInstanceOffline(EntityFullJid jid)
    {
        logger.info(getLogName() + ": " + jid + " went offline");

        EventAdmin eventAdmin = eventAdminRef.get();
        if (eventAdmin != null)
        {
            eventAdmin.postEvent(
                    JibriEvent.newWentOfflineEvent(jid, this.isSIP));
        }
        else
        {
            logger.warn("No EventAdmin!");
        }
    }

    private void notifyJibriStatus(EntityFullJid jibriJid, boolean available)
    {
        logger.info(
            getLogName() + ": " + jibriJid + " available: " + available);

        EventAdmin eventAdmin = eventAdminRef.get();
        if (eventAdmin != null)
        {
            eventAdmin.postEvent(
                    JibriEvent.newStatusChangedEvent(
                            jibriJid, available, isSIP));
        }
        else
        {
            logger.warn("No EventAdmin!");
        }
    }
}
