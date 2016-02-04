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

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;

import org.jitsi.protocol.xmpp.util.*;

import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;

import java.util.*;

import static org.junit.Assert.assertEquals;


/**

 */
@RunWith(JUnit4.class)
public class MediaSSRCMapTest
{
    /**
     * Test for remove SSRC operation(only basic scenario).
     */
    @Test
    public void testSSRCRemove()
    {
        MediaSSRCMap ssrcMap = new MediaSSRCMap();

        SourcePacketExtension audioSSRC1
            = SSRCUtil.createSSRC(12312435L,
            new String[][]{
                {"cname", "valsda42342!!@#duAppppa"},
                {"msid", "sd-saf-2-3-rf2-r43"}
            });
        SourcePacketExtension audioSSRC2
            = SSRCUtil.createSSRC(5463455L,
            new String[][]{
                {"cname", "valsda42342!!@#duAppppa"},
                {"msid", "sd-saf-2-3-rf2-r43"}
            });

        SourcePacketExtension videoSSRC1
            = SSRCUtil.createSSRC(954968935L,
            new String[][]{
                {"cname", "valsdsdf@$@#a42342!!@#duAppppa"},
                {"msid", "bfghjh56-udff2-r43"}
            });
        SourcePacketExtension videoSSRC2
            = SSRCUtil.createSSRC(6456345L,
            new String[][]{
                {"cname", "valsdsdf@$@#a42342!!@#duAppppa"},
                {"msid", "bfghjh56-udff2-r43"}
            });
        SourcePacketExtension videoSSRC3
            = SSRCUtil.createSSRC(3645473245L,
            new String[][]{
                {"cname", "valsdsdf@$@#a42342!!@#duAppppa"},
                {"msid", "bfghjh56-udff2-r43"}
            });

        ssrcMap.addSSRC("audio", audioSSRC1);
        ssrcMap.addSSRC("audio", audioSSRC2);

        ssrcMap.addSSRC("video", videoSSRC1);
        ssrcMap.addSSRC("video", videoSSRC2);
        ssrcMap.addSSRC("video", videoSSRC3);

        MediaSSRCMap toBeRemoved = new MediaSSRCMap();
        toBeRemoved.addSSRC("audio", audioSSRC1.copy());
        toBeRemoved.addSSRC("video", videoSSRC1.copy());
        toBeRemoved.addSSRC("video", videoSSRC2.copy());

        MediaSSRCMap removed = ssrcMap.remove(toBeRemoved);

        compareSSRCs(
            toBeRemoved.getSSRCsForMedia("audio"),
            removed.getSSRCsForMedia("audio"));

        compareSSRCs(
            toBeRemoved.getSSRCsForMedia("video"),
            removed.getSSRCsForMedia("video"));
    }

    private void compareSSRCs(List<SourcePacketExtension> ssrcList1,
                              List<SourcePacketExtension> ssrcList2)
    {
        assertEquals(ssrcList1.size(), ssrcList2.size());

        for (int i=0; i < ssrcList1.size(); i++)
        {
            compareSSRC(ssrcList1.get(i), ssrcList2.get(i));
        }
    }

    private void compareSSRC(SourcePacketExtension ssrc1,
                             SourcePacketExtension ssrc2)
    {
        assertEquals(ssrc1.getSSRC(), ssrc2.getSSRC());

        List<ParameterPacketExtension> params1 = ssrc1.getParameters();
        List<ParameterPacketExtension> params2 = ssrc2.getParameters();

        assertEquals(params1.size(), params2.size());

        for (int i=0; i<params1.size(); i++)
        {
            compareParam(params1.get(i), params2.get(i));
        }
    }

    private void compareParam(ParameterPacketExtension pe1,
                              ParameterPacketExtension pe2)
    {
        assertEquals(pe1.getName(), pe2.getName());
        assertEquals(pe1.getValue(), pe2.getValue());
    }
}
