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
package org.jitsi.jicofo.jigasi;

import org.jitsi.xmpp.extensions.colibri.*;
import org.jitsi.jicofo.*;
import org.jitsi.jicofo.xmpp.*;
import org.jxmpp.jid.*;
import static org.jitsi.jicofo.Bridge.*;

import java.util.*;
import java.util.stream.*;

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
    public static final String STAT_NAME_PARTICIPANTS = "participants";

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
     * The name of the stat that indicates the instance is or not a transcriber.
     */
    public static final String STAT_NAME_TRANSCRIBER = "transcriber";

    /**
     * The name of the stat that indicates the instance is or not sipgw.
     */
    public static final String STAT_NAME_SIPGW = "sipgw";

    /**
     * The local region of the this jicofo instance.
     */
    private final String localRegion;

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
        String breweryName,
        String localRegion)
    {
        super(protocolProvider,
            breweryName,
            ColibriStatsExtension.ELEMENT_NAME,
            ColibriStatsExtension.NAMESPACE);

        this.localRegion = localRegion;
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
     * @param preferredRegions a list of preferred regions.
     * @return XMPP address of Jigasi instance or <tt>null</tt> if there are
     * no Jigasis available currently.
     */
    public Jid selectTranscriber(
        List<Jid> filter, List<String> preferredRegions)
    {
        return this.selectJigasi(
            instances, filter, preferredRegions, localRegion, true);
    }

    /**
     * Selects the jigasi instance that is less loaded.
     *
     * @param filter a list of <tt>Jid</tt>s to be filtered from the list of
     * available Jigasi instances. List that we do not want as a result.
     * @param preferredRegions a list of preferred regions.
     * @return XMPP address of Jigasi instance or <tt>null</tt> if there are
     * no Jigasis available currently.
     */
    public Jid selectJigasi(List<Jid> filter, List<String> preferredRegions)
    {
        return selectJigasi(
            instances, filter, preferredRegions, localRegion, false);
    }

    /**
     * Selects the jigasi instance that is less loaded.
     *
     * @param filter a list of <tt>Jid</tt>s to be filtered from the list of
     * available Jigasi instances. List that we do not want as a result.
     * @param preferredRegions a list of preferred regions.
     * @param transcriber Whether we need to select a transcriber or sipgw
     * jigasi instance.
     * @return XMPP address of Jigasi instance or <tt>null</tt> if there are
     * no Jigasis available currently.
     */
    public static Jid selectJigasi(
        List<BrewInstance> instances,
        List<Jid> filter,
        List<String> preferredRegions,
        String localRegion,
        boolean transcriber)
    {
        // let's filter using the provided filter and those in graceful shutdown
        List<BrewInstance> filteredInstances = instances.stream()
            .filter(j -> filter == null || !filter.contains(j.jid))
            .filter(j -> j.status == null
                || !Boolean.parseBoolean(j.status.getValueAsString(
                        STAT_NAME_SHUTDOWN_IN_PROGRESS)))
            .collect(Collectors.toList());

        // let's select by type, is it transcriber or sipgw
        List<BrewInstance> selectedByCap =
            filteredInstances.stream()
            .filter(j ->  j.status != null
                    && transcriber ?
                        Boolean.parseBoolean(
                            j.status.getValueAsString(STAT_NAME_TRANSCRIBER))
                        : Boolean.parseBoolean(
                            j.status.getValueAsString(STAT_NAME_SIPGW)))
            .collect(Collectors.toList());

        if (selectedByCap.isEmpty())
        {
            // maybe old jigasi instances are used where there is no stat
            // with info is it transcriber or sipgw so let's check are we in
            // this legacy mode

            boolean legacyMode =
                filteredInstances.stream().anyMatch(
                    j -> j.status != null
                        && (j.status.getValue(STAT_NAME_TRANSCRIBER) == null
                            && j.status.getValue(STAT_NAME_SIPGW) == null));

            if (legacyMode)
            {
                selectedByCap = filteredInstances;
            }
        }

        // let's select by region
        // Prefer a jigasi in the participant's region.
        List<BrewInstance> filteredByRegion = new ArrayList<>();
        if (preferredRegions != null && !preferredRegions.isEmpty())
        {
            filteredByRegion = selectedByCap.stream()
                .filter(j -> j.status == null
                            || preferredRegions.contains(
                                j.status.getValueAsString(STAT_NAME_REGION)))
                .collect(Collectors.toList());
        }

        // Otherwise, prefer a jigasi in the local region.
        if (filteredByRegion.isEmpty() && localRegion != null)
        {
            filteredByRegion = selectedByCap.stream()
                .filter(j -> j.status == null
                    || j.status.getValueAsString(STAT_NAME_REGION)
                        .equals(localRegion))
                .collect(Collectors.toList());
        }

        // Otherwise, just ignore region filtering
        if (filteredByRegion.isEmpty())
        {
            filteredByRegion = selectedByCap;
        }

        BrewInstance lessLoadedInstance = null;
        int numberOfParticipants = Integer.MAX_VALUE;
        for (BrewInstance jigasi : filteredByRegion)
        {
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
