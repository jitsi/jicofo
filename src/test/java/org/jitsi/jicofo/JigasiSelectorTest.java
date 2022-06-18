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

import mock.xmpp.*;
import org.jitsi.jicofo.jigasi.*;
import org.jitsi.xmpp.extensions.colibri.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.Test;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;
import org.jxmpp.stringprep.*;

import java.util.*;

import static org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.*;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.*;

public class JigasiSelectorTest
{
    private static EntityBareJid roomJid;
    static
    {
        try
        {
            roomJid = JidCreate.entityBareFrom("roomName@muc-servicename.jabserver.com");
        }
        catch (XmppStringprepException e)
        {
            roomJid = null;
        }
    }

    private final JicofoHarness harness = new JicofoHarness();
    private final MockBrewery<ColibriStatsExtension> brewery
        = new MockBrewery<>(harness.jicofoServices.getXmppServices().getClientConnection(), roomJid);

    private int numberOfInstances = 0;

    @AfterEach
    public void tearDown()
    {
        harness.shutdown();
    }

    private EntityFullJid createAndAddInstance()
        throws Exception
    {
        EntityFullJid jid = JidCreate.entityFullFrom("JigasiBrewery@example.com/jigasi-" + (++numberOfInstances));

        brewery.addNewBrewInstance(jid, new ColibriStatsExtension());

        return jid;
    }

    private void updateStats(
        EntityFullJid jid,
        int numberOfParticipants,
        String region,
        Boolean inGracefulShutdown,
        boolean transcriber,
        boolean sip)
    {
        ColibriStatsExtension stats = new ColibriStatsExtension();

        if (numberOfParticipants > -1)
        {
            stats.addStat(new ColibriStatsExtension.Stat(PARTICIPANTS, numberOfParticipants));
        }

        if (region != null)
        {
            stats.addStat(new ColibriStatsExtension.Stat(REGION, region));
        }

        if (inGracefulShutdown != null)
        {
            stats.addStat(new ColibriStatsExtension.Stat(
                SHUTDOWN_IN_PROGRESS,
                inGracefulShutdown));
        }

        if (transcriber)
        {
            stats.addStat(new ColibriStatsExtension.Stat(SUPPORTS_TRANSCRIPTION, true));
        }

        if (sip)
        {
            stats.addStat(new ColibriStatsExtension.Stat(SUPPORTS_SIP, true));
        }

        brewery.updateInstanceStats(jid, stats);

    }

    @Test
    public void selectorTest()
        throws Exception
    {
        EntityFullJid jid1 = createAndAddInstance();
        EntityFullJid jid2 = createAndAddInstance();

        updateStats(jid1, 1, null, null, true, true);
        updateStats(jid2, 2, null, null, true, true);

        Jid res;

        // graceful shutdown
        updateStats(jid1, 1, null, true, true, true);
        res = JigasiDetector.selectJigasi(
            brewery.getInstances(),
            emptyList(),        /* exclude */
            emptyList(),        /* preferred regions */
            null,               /* local region */
            false);             /* select transcriber*/
        assertEquals(jid2, res, "Wrong jigasi selected");

        // select by preferred regions
        // should select based on participant as no region reported by instances
        updateStats(jid1, 1, null, null, true, true);
        updateStats(jid2, 2, null, null, true, true);
        res = JigasiDetector.selectJigasi(
            brewery.getInstances(),
            emptyList(),                            /* exclude */
            List.of("region2", "region3"),           /* preferred regions */
            "region2",                              /* local region */
            false);                                 /* select transcriber*/
        assertEquals(jid1, res, "Wrong jigasi selected");

        updateStats(jid1, 1, "region1", null, true, true);
        updateStats(jid2, 2, "region2", null, true, true);
        // should select from region2
        res = JigasiDetector.selectJigasi(
            brewery.getInstances(),
            emptyList(),                            /* exclude */
            List.of("region2", "region3"),           /* preferred regions */
            null,                                   /* local region */
            false);                                 /* select transcriber*/
        assertEquals(jid2, res, "Wrong jigasi selected");
        // no matching region, selects based on participants
        res = JigasiDetector.selectJigasi(
            brewery.getInstances(),
            emptyList(),                /* exclude */
            singletonList("region3"),   /* preferred regions */
            null,                                   /* local region */
            false);                                 /* select transcriber*/
        assertEquals(jid1, res, "Wrong jigasi selected");

        // select by local region
        // no matching region, selects based on local region
        res = JigasiDetector.selectJigasi(
            brewery.getInstances(),
            emptyList(),                            /* exclude */
            singletonList("region3"),               /* preferred regions */
            "region2",                              /* local region */
            false);                                 /* select transcriber*/
        assertEquals(jid2, res, "Wrong jigasi selected");
        res = JigasiDetector.selectJigasi(
            brewery.getInstances(),
            emptyList(),        /* exclude */
            emptyList(),        /* preferred regions */
            "region2",          /* local region */
            false);             /* select transcriber*/
        assertEquals(jid2, res, "Wrong jigasi selected");

        // filter
        // should select from region2, but that is filtered so will select
        // based on participants -> jid1
        res = JigasiDetector.selectJigasi(
            brewery.getInstances(),
            singletonList(jid2),                     /* exclude */
            List.of("region2", "region3"),            /* preferred regions */
            null,                                    /* local region */
            false);                                  /* select transcriber*/
        assertEquals(jid1, res, "Wrong jigasi selected");


        // select transcriber
        updateStats(jid1, 1, "region1", null, false, true);
        updateStats(jid2, 2, "region2", null, false, true);

        // let's try find transcriber when there are no transcribers
        // and just sipgw, should not match anything
        res = JigasiDetector.selectJigasi(
            brewery.getInstances(),
            emptyList(),        /* exclude */
            emptyList(),        /* preferred regions */
            null,               /* local region */
            true);              /* select transcriber*/
        assertNull(res, "Wrong jigasi selected");

        EntityFullJid jid3 = createAndAddInstance();
        EntityFullJid jid4 = createAndAddInstance();

        updateStats(jid3, 1, "region1", null, true, false);
        updateStats(jid4, 2, "region2", null, true, false);
        res = JigasiDetector.selectJigasi(
            brewery.getInstances(),
            emptyList(),        /* exclude */
            emptyList(),        /* preferred regions */
            null,               /* local region */
            true);              /* select transcriber*/
        assertEquals(jid3, res, "Wrong jigasi selected");

        // select sipgw
        res = JigasiDetector.selectJigasi(
            brewery.getInstances(),
            emptyList(),        /* exclude */
            emptyList(),        /* preferred regions */
            null,               /* local region */
            false);             /* select transcriber*/
        assertEquals(jid1, res, "Wrong jigasi selected");

        // transcriber from local region2
        res = JigasiDetector.selectJigasi(
            brewery.getInstances(),
            emptyList(),        /* exclude */
            emptyList(),        /* preferred regions */
            "region2",          /* local region */
            true);              /* select transcriber*/
        assertEquals(jid4, res, "Wrong jigasi selected");

        // transcriber from region2
        res = JigasiDetector.selectJigasi(
            brewery.getInstances(),
            emptyList(),                             /* exclude */
            List.of("region2", "region3"),            /* preferred regions */
            null,                                    /* local region */
            true);                                   /* select transcriber*/
        assertEquals(jid4, res, "Wrong jigasi selected");

        // transcriber no matching region, select based on participants
        res = JigasiDetector.selectJigasi(
            brewery.getInstances(),
            emptyList(),                            /* exclude */
            singletonList("region3"),               /* preferred regions */
            null,                                   /* local region */
            true);                                  /* select transcriber*/
        assertEquals(jid3, res, "Wrong jigasi selected");

        // transcriber no matching region, select based on participants, but
        // with filtered jid3(which has lowest number of participants)
        res = JigasiDetector.selectJigasi(
            brewery.getInstances(),
            singletonList(jid3),                    /* exclude */
            singletonList("region3"),               /* preferred regions */
            null,                                   /* local region */
            true);                                  /* select transcriber*/
        assertEquals(jid4, res, "Wrong jigasi selected");
    }
}
