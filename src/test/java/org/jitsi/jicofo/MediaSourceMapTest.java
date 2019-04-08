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

import org.jitsi.xmpp.extensions.colibri.*;
import org.jitsi.xmpp.extensions.jingle.*;

import org.jitsi.protocol.xmpp.util.*;

import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;

import java.util.*;

import static org.junit.Assert.assertEquals;


/**

 */
@RunWith(JUnit4.class)
public class MediaSourceMapTest
{

    @Test
    public void testGetSourcesSsrcAndRid()
    {
        /**
         * Test to make sure both SSRC and RID sources are extracted
         */
        RtpDescriptionPacketExtension rtpDescriptionPacketExtension =
                new RtpDescriptionPacketExtension();
        rtpDescriptionPacketExtension.setMedia("video");

        SourcePacketExtension ssrcOne = new SourcePacketExtension();
        ssrcOne.setSSRC(12345678L);
        rtpDescriptionPacketExtension.addChildExtension(ssrcOne);

        SourcePacketExtension ssrcTwo = new SourcePacketExtension();
        ssrcTwo.setSSRC(23456789L);
        rtpDescriptionPacketExtension.addChildExtension(ssrcTwo);

        SourcePacketExtension ridOne = new SourcePacketExtension();
        ridOne.setAttribute("rid", "1");
        rtpDescriptionPacketExtension.addChildExtension(ridOne);

        SourcePacketExtension ridTwo = new SourcePacketExtension();
        ridTwo.setAttribute("rid", "2");
        rtpDescriptionPacketExtension.addChildExtension(ridTwo);

        ContentPacketExtension content = new ContentPacketExtension();
        content.setName("video");
        content.addChildExtension(rtpDescriptionPacketExtension);

        MediaSourceMap result = MediaSourceMap.getSourcesFromContent(Collections.singletonList(content));
        List<SourcePacketExtension> sources = result.getSourcesForMedia("video");
        assertEquals(4, sources.size());
    }
    /**
     * Test for remove SSRC operation(only basic scenario).
     */
    @Test
    public void testSSRCRemove()
    {
        MediaSourceMap ssrcMap = new MediaSourceMap();

        SourcePacketExtension audioSSRC1
            = SourceUtil.createSourceWithSsrc(12312435L,
            new String[][]{
                {"cname", "valsda42342!!@#duAppppa"},
                {"msid", "sd-saf-2-3-rf2-r43"}
            });
        SourcePacketExtension audioSSRC2
            = SourceUtil.createSourceWithSsrc(5463455L,
            new String[][]{
                {"cname", "valsda42342!!@#duAppppa"},
                {"msid", "sd-saf-2-3-rf2-r43"}
            });

        SourcePacketExtension videoSSRC1
            = SourceUtil.createSourceWithSsrc(954968935L,
            new String[][]{
                {"cname", "valsdsdf@$@#a42342!!@#duAppppa"},
                {"msid", "bfghjh56-udff2-r43"}
            });
        SourcePacketExtension videoSSRC2
            = SourceUtil.createSourceWithSsrc(6456345L,
            new String[][]{
                {"cname", "valsdsdf@$@#a42342!!@#duAppppa"},
                {"msid", "bfghjh56-udff2-r43"}
            });
        SourcePacketExtension videoSSRC3
            = SourceUtil.createSourceWithSsrc(3645473245L,
            new String[][]{
                {"cname", "valsdsdf@$@#a42342!!@#duAppppa"},
                {"msid", "bfghjh56-udff2-r43"}
            });

        ssrcMap.addSource("audio", audioSSRC1);
        ssrcMap.addSource("audio", audioSSRC2);

        ssrcMap.addSource("video", videoSSRC1);
        ssrcMap.addSource("video", videoSSRC2);
        ssrcMap.addSource("video", videoSSRC3);

        MediaSourceMap toBeRemoved = new MediaSourceMap();
        toBeRemoved.addSource("audio", audioSSRC1.copy());
        toBeRemoved.addSource("video", videoSSRC1.copy());
        toBeRemoved.addSource("video", videoSSRC2.copy());

        MediaSourceMap removed = ssrcMap.remove(toBeRemoved);

        compareSSRCs(
            toBeRemoved.getSourcesForMedia("audio"),
            removed.getSourcesForMedia("audio"));

        compareSSRCs(
            toBeRemoved.getSourcesForMedia("video"),
            removed.getSourcesForMedia("video"));
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
