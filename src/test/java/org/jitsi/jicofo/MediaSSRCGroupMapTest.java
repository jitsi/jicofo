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

import static org.junit.Assert.*;

/**
 * Tests for {@link MediaSSRCGroupMap}
 *
 * @author Pawel Domas
 */
@RunWith(JUnit4.class)
public class MediaSSRCGroupMapTest
{
    /**
     * Basic test scenario for remove operation
     */
    @Test
    public void testRemoveSSRCGroup()
    {
        MediaSSRCGroupMap ssrcGroups = new MediaSSRCGroupMap();

        SSRCGroup audioGroup1 = SSRCUtil.createSSRCGroup(
            SourceGroupPacketExtension.SEMANTICS_FID,
            new SourcePacketExtension[] {
                SSRCUtil.createSSRC(345646L, new String[][]{
                    {"cname", "bfdlkbmfdl"},
                    {"msid", "3425345-fgdh-54y45-hghfgh"}
                }),
                SSRCUtil.createSSRC(786587L, new String[][]{
                    {"cname", "vxpoivoiul"},
                    {"msid", "985-54y-55mgfg7-4-yh54"}
                })
            }
        );
        SSRCGroup audioGroup2 = SSRCUtil.createSSRCGroup(
            SourceGroupPacketExtension.SEMANTICS_SIMULCAST,
            new SourcePacketExtension[] {
                SSRCUtil.createSSRC(2265675L, new String[][]{
                    {"cname", "89fd7g87dfbu"},
                    {"msid", "546-54-234-435-435"}
                }),
                SSRCUtil.createSSRC(3455667L, new String[][]{
                    {"cname", "hgj09j8gh0j8"},
                    {"msid", "657657-435-34534-5467"}
                }),
                SSRCUtil.createSSRC(8979879L, new String[][]{
                    {"cname", "7nb89m79bnm"},
                    {"msid", "4562-724575-54754-4527"}
                })
            }
        );
        SSRCGroup audioGroup3 = SSRCUtil.createSSRCGroup(
            SourceGroupPacketExtension.SEMANTICS_SIMULCAST,
            new SourcePacketExtension[] {
                SSRCUtil.createSSRC(456457L, new String[][]{
                    {"cname", "vdfvdf8789df"},
                    {"msid", "56765-756756-34534"}
                }),
                SSRCUtil.createSSRC(234325L, new String[][]{
                    {"cname", "786dsf8g8dfg6"},
                    {"msid", "678678-56756-56756-65765"}
                }),
                SSRCUtil.createSSRC(879879L, new String[][]{
                    {"cname", "oilioli9io9"},
                    {"msid", "246-452645-425645-4526"}
                })
            }
        );

        ssrcGroups.getSSRCGroupsForMedia("audio").add(audioGroup1);
        ssrcGroups.getSSRCGroupsForMedia("audio").add(audioGroup2);
        ssrcGroups.getSSRCGroupsForMedia("audio").add(audioGroup3);

        SSRCGroup videoGroup1 = SSRCUtil.createSSRCGroup(
            SourceGroupPacketExtension.SEMANTICS_SIMULCAST,
            new SourcePacketExtension[] {
                SSRCUtil.createSSRC(2345346L, new String[][]{
                    {"cname", "342kj5hk3h5"},
                    {"msid", "476457-4355-456546-456"}
                }),
                SSRCUtil.createSSRC(768678L, new String[][]{
                    {"cname", "546lkjn45lk"},
                    {"msid", "245634-24536-2456-4526"}
                }),
                SSRCUtil.createSSRC(5646L, new String[][]{
                    {"cname", "32lk4j3l232"},
                    {"msid", "7654-5467-5647435-345"}
                }),
                SSRCUtil.createSSRC(2357561L, new String[][]{
                    {"cname", "kl65j7kl56jl"},
                    {"msid", "5747-4355-56723-34557"}
                })
            }
        );
        SSRCGroup videoGroup2 = SSRCUtil.createSSRCGroup(
            SourceGroupPacketExtension.SEMANTICS_SIMULCAST,
            new SourcePacketExtension[] {
                SSRCUtil.createSSRC(8768678L, new String[][]{
                    {"cname", "fdg897g98"},
                    {"msid", "675768-678-678-678-65787"}
                }),
                SSRCUtil.createSSRC(3543556L, new String[][]{
                    {"cname", "5bv3n5b"},
                    {"msid", "6758-6786456-567856-86758"}
                }),
                SSRCUtil.createSSRC(5675634L, new String[][]{
                    {"cname", "67klm8lk768l"},
                    {"msid", "5436-54-6-45365437-567567"}
                })
            }
        );

        ssrcGroups.getSSRCGroupsForMedia("video").add(videoGroup1);
        ssrcGroups.getSSRCGroupsForMedia("video").add(videoGroup2);

        MediaSSRCGroupMap toRemove = new MediaSSRCGroupMap();
        toRemove.getSSRCGroupsForMedia("audio").add(audioGroup3.copy());
        toRemove.getSSRCGroupsForMedia("audio").add(audioGroup1.copy());
        toRemove.getSSRCGroupsForMedia("video").add(videoGroup2.copy());

        /*toRemove.getSSRCGroupsForMedia("audio").add(
            SSRCUtil.createSSRCGroup(
                SourceGroupPacketExtension.SEMANTICS_SIMULCAST,
                new SourcePacketExtension[]{
                    SSRCUtil.createSSRC(324234L, new String[][]{
                        {"cname", "dfggdf"},
                        {"msid", "bfgb88-323fgb-12gfgfh-fghy54"},
                    })
                })
        );*/

        MediaSSRCGroupMap removed = ssrcGroups.remove(toRemove);

        compareGroupMaps(toRemove, removed);
    }

    private void compareGroupMaps(MediaSSRCGroupMap g1, MediaSSRCGroupMap g2)
    {
        List<String> mediaTypes1 = g1.getMediaTypes();
        List<String> mediaTypes2 = g2.getMediaTypes();

        assertEquals(mediaTypes1, mediaTypes2);

        for (String mediaType : mediaTypes1)
        {
            assertEquals(
                g1.getSSRCGroupsForMedia(mediaType),
                g2.getSSRCGroupsForMedia(mediaType));
        }
    }


}
