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
import static org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.*;

import org.jitsi.jicofo.*;
import org.jitsi.jicofo.xmpp.*;
import org.json.simple.*;
import org.jxmpp.jid.*;

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
     * The local region of the this jicofo instance.
     */
    private final String localRegion = JicofoConfig.config.localRegion();

    /**
     * Constructs new JigasiDetector.
     *
     * @param protocolProvider the xmpp protocol provider
     * @param breweryName the room name, can be just roomName, then the muc
     * service will be discovered from server and in case of multiple will use
     * the first one. Or it can be full room id:
     * roomName@muc-servicename.jabserver.com.
     */
    public JigasiDetector(ProtocolProviderHandler protocolProvider, String breweryName)
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
     * Selects a jigasi instance which supports transcription.
     * @param exclude a list of <tt>Jid</tt>s to be filtered from the list of
     * available Jigasi instances. List that we do not want as a result.
     * @param preferredRegions a list of preferred regions.
     * @return XMPP address of Jigasi instance or <tt>null</tt> if there are
     * no Jigasis available currently.
     */
    public Jid selectTranscriber(
        List<Jid> exclude, Collection<String> preferredRegions)
    {
        return JigasiDetector.selectJigasi(
            instances, exclude, preferredRegions, localRegion, true);
    }

    /**
     * Selects the jigasi instance that is less loaded.
     *
     * @param exclude a list of <tt>Jid</tt>s to be filtered from the list of
     * available Jigasi instances. List that we do not want as a result.
     * @param preferredRegions a list of preferred regions.
     * @return XMPP address of Jigasi instance or <tt>null</tt> if there are
     * no Jigasis available currently.
     */
    public Jid selectJigasi(
        List<Jid> exclude, Collection<String> preferredRegions)
    {
        return selectJigasi(
            instances, exclude, preferredRegions, localRegion, false);
    }

    /**
     * Selects the jigasi instance that is less loaded.
     *
     * @param exclude a list of <tt>Jid</tt>s to be filtered from the list of
     * available Jigasi instances. List that we do not want as a result.
     * @param preferredRegions a list of preferred regions.
     * @param transcriber Whether we need to select a transcriber or sipgw
     * jigasi instance.
     * @return XMPP address of Jigasi instance or <tt>null</tt> if there are
     * no Jigasis available currently.
     */
    public static Jid selectJigasi(
        List<BrewInstance> instances,
        List<Jid> exclude,
        Collection<String> preferredRegions,
        String localRegion,
        boolean transcriber)
    {
        final Collection<String> regions
            = preferredRegions != null ? preferredRegions : new ArrayList<>();

        // let's filter using the provided exclude list
        // and those in graceful shutdown
        List<BrewInstance> filteredInstances = instances.stream()
            .filter(j -> exclude == null || !exclude.contains(j.jid))
            .filter(j -> !isInGracefulShutdown(j))
            .collect(Collectors.toList());

        // let's select by type, is it transcriber or sipgw
        List<BrewInstance> selectedByCap =
            filteredInstances.stream()
            .filter(j ->  transcriber ? supportTranscription(j) : supportSip(j))
            .collect(Collectors.toList());

        if (selectedByCap.isEmpty())
        {
            // maybe old jigasi instances are used where there is no stat
            // with info is it transcriber or sipgw so let's check are we in
            // this legacy mode

            boolean legacyMode = filteredInstances.stream()
                .anyMatch(JigasiDetector::isLegacyInstance);

            if (legacyMode)
            {
                selectedByCap = filteredInstances;
            }

            // but now let's check whether we are in mixed mode (legacy and new)
            boolean mixedMode = filteredInstances.stream()
                .anyMatch(bi -> !isLegacyInstance(bi));

            if (mixedMode)
            {
                // one of those lists should be non empty
                List<BrewInstance> transcribers =
                    selectedByCap.stream()
                        .filter(JigasiDetector::supportTranscription)
                        .collect(Collectors.toList());
                List<BrewInstance> sipgwInstances =
                    selectedByCap.stream()
                        .filter(JigasiDetector::supportSip)
                        .collect(Collectors.toList());

                // We have two options:
                // - sipgw is new and reports its cap and old transcriber
                // - transcribers are new and reports cap and old sipgw

                if(!transcribers.isEmpty())
                {
                    // new transcribers and old sipgw
                    if(transcriber)
                    {
                        selectedByCap = transcribers;
                    }
                    else
                    {
                        // we need those that are not transcribers
                        selectedByCap = selectedByCap.stream()
                            .filter(j -> !supportTranscription(j))
                            .collect(Collectors.toList());
                    }
                }
                else if(!sipgwInstances.isEmpty())
                {
                    // new sipgw and old transcribers
                    if(transcriber)
                    {
                        // we need those that are not sipgw
                        selectedByCap = selectedByCap.stream()
                            .filter(j -> !supportSip(j))
                            .collect(Collectors.toList());
                    }
                    else
                    {
                        selectedByCap = sipgwInstances;
                    }
                }
            }
        }

        // let's select by region
        // Prefer a jigasi in the participant's region.
        List<BrewInstance> filteredByRegion = new ArrayList<>();
        if (preferredRegions != null && !preferredRegions.isEmpty())
        {
            filteredByRegion = selectedByCap.stream()
                .filter(j -> isInPreferredRegion(j, regions))
                .collect(Collectors.toList());
        }

        // Otherwise, prefer a jigasi in the local region.
        if (filteredByRegion.isEmpty() && localRegion != null)
        {
            filteredByRegion = selectedByCap.stream()
                .filter(j -> isInRegion(j, localRegion))
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
            int currentParticipants = getParticipantsCount(jigasi);
            if (currentParticipants < numberOfParticipants)
            {
                numberOfParticipants = currentParticipants;
                lessLoadedInstance = jigasi;
            }
        }

        return lessLoadedInstance != null ? lessLoadedInstance.jid : null;
    }

    /**
     * Checks whether the {@code BrewInstance} is in graceful shutdown.
     * @param bi the {@code BrewInstance} to check.
     * @return whether the {@code BrewInstance} is in graceful shutdown.
     */
    private static boolean isInGracefulShutdown(BrewInstance bi)
    {
        return bi.status != null
            && Boolean.parseBoolean(
                bi.status.getValueAsString(SHUTDOWN_IN_PROGRESS));
    }

    /**
     * Checks whether the {@code BrewInstance} supports transcription.
     * @param bi the {@code BrewInstance} to check.
     * @return whether the {@code BrewInstance} supports transcription.
     */
    private static boolean supportTranscription(BrewInstance bi)
    {
        return bi.status != null
            && Boolean.parseBoolean(
                bi.status.getValueAsString(SUPPORTS_TRANSCRIPTION));
    }

    /**
     * Checks whether the {@code BrewInstance} supports sip.
     * @param bi the {@code BrewInstance} to check.
     * @return whether the {@code BrewInstance} supports sip.
     */
    private static boolean supportSip(BrewInstance bi)
    {
        return bi.status != null
            && Boolean.parseBoolean(
                bi.status.getValueAsString(SUPPORTS_SIP));
    }

    /**
     * Checks whether the {@code BrewInstance} is a legacy instance.
     * A legacy instance is the one that has stats and both support sip and
     * transcription stats are not set (are null).
     * @param bi the {@code BrewInstance} to check.
     * @return whether the {@code BrewInstance} is legacy.
     */
    private static boolean isLegacyInstance(BrewInstance bi)
    {
        return bi.status != null
            && (bi.status.getValue(SUPPORTS_TRANSCRIPTION) == null
                && bi.status.getValue(SUPPORTS_SIP) == null);
    }

    /**
     * Checks whether the {@code BrewInstance} is in a preferred region.
     * @param bi the {@code BrewInstance} to check.
     * @param preferredRegions a list of preferred regions.
     * @return whether the {@code BrewInstance} is in a preferred region.
     */
    private static boolean isInPreferredRegion(
        BrewInstance bi, Collection<String> preferredRegions)
    {
        return bi.status != null
            && preferredRegions.contains(bi.status.getValueAsString(REGION));
    }

    /**
     * Checks whether the {@code BrewInstance} is in a region.
     * @param bi the {@code BrewInstance} to check.
     * @param region a region to check.
     * @return whether the {@code BrewInstance} is in a region.
     */
    private static boolean isInRegion(
        BrewInstance bi, String region)
    {
        return bi.status != null
            && region.equals(bi.status.getValueAsString(REGION));
    }

    /**
     * Returns the number pf participants reported by a {@code BrewInstance}.
     * @param bi the {@code BrewInstance} to check.
     * @return the number of participants or 0 if nothing reported.
     */
    private static int getParticipantsCount(
        BrewInstance bi)
    {
        return bi.status != null ? bi.status.getValueAsInt(PARTICIPANTS): 0;
    }

    public int getJigasiSipCount()
    {
        return (int) instances.stream().filter(i -> supportSip(i)).count();
    }

    public int getJigasiSipInGracefulShutdownCount()
    {
        return (int) instances.stream()
            .filter(i -> supportSip(i))
            .filter(i -> isInGracefulShutdown(i)).count();
    }

    public int getJigasiTranscriberCount()
    {
        return (int) instances.stream().filter(i -> supportTranscription(i)).count();
    }

    @SuppressWarnings("unchecked")
    public JSONObject getStats()
    {
        JSONObject stats = new JSONObject();
        stats.put("sip_count", getJigasiSipCount());
        stats.put("sip_in_graceful_shutdown_count",
            getJigasiSipInGracefulShutdownCount());
        stats.put("transcriber_count", getJigasiTranscriberCount());

        return stats;
    }
}
