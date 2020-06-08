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
import org.junit.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;
import static org.jitsi.xmpp.extensions.colibri.ColibriStatsExtension.*;

import java.util.*;

import static org.junit.Assert.*;

public class JigasiSelectorTest
{
    private static MockBrewery<ColibriStatsExtension> brewery;

    private static int numberOfInstances = 0;

    @BeforeClass
    public static void setUpClass()
        throws Exception
    {
        brewery = new MockBrewery<>(
            new ProtocolProviderHandler(),
            "roomName@muc-servicename.jabserver.com"
        );
    }

    private Jid createAndAddInstance()
        throws Exception
    {
        Jid jid = JidCreate.from("jigasi-" + (++numberOfInstances));

        brewery.addNewBrewInstance(
            jid,
            new ColibriStatsExtension()
        );

        return jid;
    }

    private void updateStats(
        Jid jid,
        int numberOfParticipants,
        String region,
        Boolean inGracefulShutdown,
        Boolean transcriber,
        Boolean sipgw)
    {
        ColibriStatsExtension stats = new ColibriStatsExtension();

        if (numberOfParticipants > -1)
        {
            stats.addStat(new ColibriStatsExtension.Stat(
                PARTICIPANTS, numberOfParticipants));
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

        if (transcriber != null)
        {
            stats.addStat(new ColibriStatsExtension.Stat(
                SUPPORTS_TRANSCRIPTION, transcriber));
        }

        if (sipgw != null)
        {
            stats.addStat(new ColibriStatsExtension.Stat(SUPPORTS_SIP, sipgw));
        }

        brewery.updateInstanceStats(jid, stats);

    }

    @Test
    public void selectorTest()
        throws Exception
    {
        Jid jid1 = createAndAddInstance();
        Jid jid2 = createAndAddInstance();

        updateStats(jid1, 1, null, null, null, null);
        updateStats(jid2, 2, null, null, null, null);

        Jid res;
        // legacy select where there is no stats(transcriber, sipgw)
        // and no region,
        // also checks whether selectJigasi can get null list of regions
        res = JigasiDetector.selectJigasi(
            brewery.getInstances(),
            null,               /* filter */
            null,               /* preferred regions */
            null,               /* local region */
            false);             /* select transcriber*/
        assertEquals("Wrong jigasi selected", jid1, res);

        // legacy & filter
        res = JigasiDetector.selectJigasi(
            brewery.getInstances(),
            Arrays.asList(new Jid[]{jid1}), /* filter */
            new ArrayList<>(),              /* preferred regions */
            null,                           /* local region */
            false);                         /* select transcriber*/
        assertEquals("Wrong jigasi selected", jid2, res);

        // graceful shutdown
        updateStats(jid1, 1, null, true, null, null);
        res = JigasiDetector.selectJigasi(
            brewery.getInstances(),
            null,               /* filter */
            new ArrayList<>(),  /* preferred regions */
            null,               /* local region */
            false);             /* select transcriber*/
        assertEquals("Wrong jigasi selected", jid2, res);

        // select by preferred regions
        // should select based on participant as no region reported by instances
        updateStats(jid1, 1, null, null, null, null);
        updateStats(jid2, 2, null, null, null, null);
        res = JigasiDetector.selectJigasi(
            brewery.getInstances(),
            null,                                   /* filter */
            Arrays.asList(
                new String[]{"region2", "region3"}),/* preferred regions */
            "region2",                              /* local region */
            false);                                 /* select transcriber*/
        assertEquals("Wrong jigasi selected", jid1, res);

        updateStats(jid1, 1, "region1", null, null, null);
        updateStats(jid2, 2, "region2", null, null, null);
        // should select from region2
        res = JigasiDetector.selectJigasi(
            brewery.getInstances(),
            null,                                   /* filter */
            Arrays.asList(
                new String[]{"region2", "region3"}),/* preferred regions */
            null,                                   /* local region */
            false);                                 /* select transcriber*/
        assertEquals("Wrong jigasi selected", jid2, res);
        // no matching region, selects based on participants
        res = JigasiDetector.selectJigasi(
            brewery.getInstances(),
            null,                                   /* filter */
            Arrays.asList(new String[]{"region3"}), /* preferred regions */
            null,                                   /* local region */
            false);                                 /* select transcriber*/
        assertEquals("Wrong jigasi selected", jid1, res);

        // select by local region
        // no matching region, selects based on local region
        res = JigasiDetector.selectJigasi(
            brewery.getInstances(),
            null,                                   /* filter */
            Arrays.asList(new String[]{"region3"}), /* preferred regions */
            "region2",                              /* local region */
            false);                                 /* select transcriber*/
        assertEquals("Wrong jigasi selected", jid2, res);
        res = JigasiDetector.selectJigasi(
            brewery.getInstances(),
            null,               /* filter */
            new ArrayList<>(),  /* preferred regions */
            "region2",          /* local region */
            false);             /* select transcriber*/
        assertEquals("Wrong jigasi selected", jid2, res);

        // filter
        // should select from region2, but that is filtered so will select
        // based on participants -> jid1
        res = JigasiDetector.selectJigasi(
            brewery.getInstances(),
            Arrays.asList(new Jid[]{jid2}),          /* filter */
            Arrays.asList(
                new String[]{"region2", "region3"}), /* preferred regions */
            null,                                    /* local region */
            false);                                  /* select transcriber*/
        assertEquals("Wrong jigasi selected", jid1, res);


        // select transcriber
        // stats are in legacy-mode, should match based on participant
        updateStats(jid1, 1, "region1", null, null, null);
        updateStats(jid2, 2, "region2", null, null, null);
        res = JigasiDetector.selectJigasi(
            brewery.getInstances(),
            null,               /* filter */
            new ArrayList<>(),  /* preferred regions */
            null,               /* local region */
            true);              /* select transcriber*/
        assertEquals("Wrong jigasi selected", jid1, res);

        // now stats are no in legacy
        updateStats(jid1, 1, "region1", null, false, true);
        updateStats(jid2, 2, "region2", null, false, true);

        // let's try find transcriber when there are no transcribers
        // and just sipgw, should not match anything as not in legacy mode
        res = JigasiDetector.selectJigasi(
            brewery.getInstances(),
            null,               /* filter */
            new ArrayList<>(),  /* preferred regions */
            null,               /* local region */
            true);              /* select transcriber*/
        assertEquals("Wrong jigasi selected", null, res);

        Jid jid3 = createAndAddInstance();
        Jid jid4 = createAndAddInstance();

        updateStats(jid3, 1, "region1", null, true, false);
        updateStats(jid4, 2, "region2", null, true, false);
        res = JigasiDetector.selectJigasi(
            brewery.getInstances(),
            null,               /* filter */
            new ArrayList<>(),  /* preferred regions */
            null,               /* local region */
            true);              /* select transcriber*/
        assertEquals("Wrong jigasi selected", jid3, res);

        // select sipgw
        res = JigasiDetector.selectJigasi(
            brewery.getInstances(),
            null,               /* filter */
            new ArrayList<>(),  /* preferred regions */
            null,               /* local region */
            false);             /* select transcriber*/
        assertEquals("Wrong jigasi selected", jid1, res);

        // transcriber from local region2
        res = JigasiDetector.selectJigasi(
            brewery.getInstances(),
            null,               /* filter */
            new ArrayList<>(),  /* preferred regions */
            "region2",          /* local region */
            true);              /* select transcriber*/
        assertEquals("Wrong jigasi selected", jid4, res);

        // transcriber from region2
        res = JigasiDetector.selectJigasi(
            brewery.getInstances(),
            null,                                    /* filter */
            Arrays.asList(
                new String[]{"region2", "region3"}), /* preferred regions */
            null,                                    /* local region */
            true);                                   /* select transcriber*/
        assertEquals("Wrong jigasi selected", jid4, res);

        // transcriber no matching region, select based on participants
        res = JigasiDetector.selectJigasi(
            brewery.getInstances(),
            null,                                   /* filter */
            Arrays.asList(new String[]{"region3"}), /* preferred regions */
            null,                                   /* local region */
            true);                                  /* select transcriber*/
        assertEquals("Wrong jigasi selected", jid3, res);

        // transcriber no matching region, select based on participants, but
        // with filtered jid3(which has lowest number of participants)
        res = JigasiDetector.selectJigasi(
            brewery.getInstances(),
            Arrays.asList(new Jid[]{jid3}),         /* filter */
            Arrays.asList(new String[]{"region3"}), /* preferred regions */
            null,                                   /* local region */
            true);                                  /* select transcriber*/
        assertEquals("Wrong jigasi selected", jid4, res);

        // mixed mode, transcriber new and legacy sip
        updateStats(jid1, 1, null, null, true, null);
        updateStats(jid2, 2, null, null, null, null);
        updateStats(jid3, 3, null, null, null, null);
        updateStats(jid4, 4, null, null, null, null);

        res = JigasiDetector.selectJigasi(
            brewery.getInstances(),
            Arrays.asList(new Jid[]{}),         /* filter */
            Arrays.asList(new String[]{}),      /* preferred regions */
            null,                               /* local region */
            false);                             /* select transcriber*/
        assertEquals("Wrong jigasi selected", jid2, res);

        res = JigasiDetector.selectJigasi(
            brewery.getInstances(),
            Arrays.asList(new Jid[]{}),         /* filter */
            Arrays.asList(new String[]{}),      /* preferred regions */
            null,                               /* local region */
            true);                             /* select transcriber*/
        assertEquals("Wrong jigasi selected", jid1, res);

        // mixed mode, legacy transcriber and new sip
        updateStats(jid1, 1, null, null, null, true);
        updateStats(jid2, 2, null, null, null, null);
        updateStats(jid3, 3, null, null, null, null);
        updateStats(jid4, 4, null, null, null, null);

        res = JigasiDetector.selectJigasi(
            brewery.getInstances(),
            Arrays.asList(new Jid[]{}),         /* filter */
            Arrays.asList(new String[]{}),      /* preferred regions */
            null,                               /* local region */
            true);                             /* select transcriber*/
        assertEquals("Wrong jigasi selected", jid2, res);

        res = JigasiDetector.selectJigasi(
            brewery.getInstances(),
            Arrays.asList(new Jid[]{}),         /* filter */
            Arrays.asList(new String[]{}),      /* preferred regions */
            null,                               /* local region */
            false);                             /* select transcriber*/
        assertEquals("Wrong jigasi selected", jid1, res);
    }
}
