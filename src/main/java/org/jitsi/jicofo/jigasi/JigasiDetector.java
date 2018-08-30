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
package org.jitsi.jicofo.jigasi;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import org.jitsi.jicofo.*;
import org.jitsi.jicofo.xmpp.*;
import org.jxmpp.jid.*;

/**
 * <tt>JigasiDetector</tt> manages the pool of Jigasi instances which exist in
 * the current session. Does that by joining "brewery" room where Jigasi connect
 * to and publish their's status in MUC presence.
 * @author Damian Minkov
 */
public class JigasiDetector
    extends BaseBrewery<ColibriStatsExtension>
{
    /**
     * The name of config property which provides the name of the MUC room in
     * which all Jigasi instances.
     * Can be just roomName, then the muc service will be discovered from server
     * and in case of multiple will use the first one.
     * Or it can be full room id: roomName@muc-servicename.jabserver.com.
     */
    public static final String JIGASI_ROOM_PNAME
        = "org.jitsi.jicofo.jigasi.BREWERY";

    /**
     * The name of the stat used by the instance to indicate the number of
     * participants. This should match
     * {@code VideobridgeStatistics.NUMBEROFPARTICIPANTS}, but is defined
     * separately to avoid depending on the {@code jitsi-videobridge}
     * maven package.
     */
    private static final String STAT_NAME_PARTICIPANTS = "participants";

    /**
     * The name of the stat that indicates the instance has entered graceful
     * shutdown mode.
     * {@code VideobridgeStatistics.SHUTDOWN_IN_PROGRESS}, but is defined
     * separately to avoid depending on the {@code jitsi-videobridge} maven
     * package.
     */
    public static final String STAT_NAME_SHUTDOWN_IN_PROGRESS
        = "graceful_shutdown";

    /**
     * Constructs new JigasiDetector.
     *
     * @param protocolProvider the xmpp protocol provider
     * @param breweryName the room name, can be just roomName, then the muc
     * service will be discovered from server and in case of multiple will use
     * the first one. Or it can be full room id:
     * roomName@muc-servicename.jabserver.com.
     */
    public JigasiDetector(
        ProtocolProviderHandler protocolProvider,
        String breweryName)
    {
        super(protocolProvider,
            breweryName,
            ColibriStatsExtension.ELEMENT_NAME,
            ColibriStatsExtension.NAMESPACE);
    }

    @Override
    protected void onInstanceStatusChanged(
        Jid jid,
        ColibriStatsExtension status)
    {}

    @Override
    protected void notifyInstanceOffline(Jid jid)
    {}

    /**
     * Selects the jigasi instance that is less loaded.
     *
     * @return XMPP address of Jigasi instance or <tt>null</tt> if there are
     * no Jigasis available currently.
     */
    public Jid selectJigasi()
    {
        BrewInstance lessLoadedInstance = null;
        int numberOfParticipants = Integer.MAX_VALUE;
        for (BrewInstance jigasi : instances)
        {
            if(jigasi.status != null
                && Boolean.valueOf(jigasi.status.getValueAsString(
                    STAT_NAME_SHUTDOWN_IN_PROGRESS)))
            {
                // skip instance which is shutting down
                continue;
            }

            int currentParticipants
                = jigasi.status != null ?
                    jigasi.status.getValueAsInt(STAT_NAME_PARTICIPANTS)
                    : 0;
            if (currentParticipants < numberOfParticipants)
            {
                numberOfParticipants = currentParticipants;
                lessLoadedInstance = jigasi;
            }
        }

        return lessLoadedInstance != null ? lessLoadedInstance.jid : null;
    }
}
