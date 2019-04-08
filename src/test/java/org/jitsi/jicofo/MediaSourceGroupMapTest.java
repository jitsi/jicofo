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

import static org.junit.Assert.*;

/**
 * Tests for {@link MediaSourceGroupMap}
 *
 * @author Pawel Domas
 */
@RunWith(JUnit4.class)
public class MediaSourceGroupMapTest
{
    @Test
    public void testContainsGroup()
    {
        /**
         * Make sure that groups with the same semantic but different source
         * types (RID vs SSRC) don't collide
         */
        MediaSourceGroupMap sourceGroups = new MediaSourceGroupMap();
        // First add an ssrc SIM group
        SourceGroup ssrcSimGroup = SourceUtil.createSourceGroup(
                SourceGroupPacketExtension.SEMANTICS_SIMULCAST,
                new SourcePacketExtension[] {
                        SourceUtil.createSourceWithSsrc(123123L, new String[][]{}),
                        SourceUtil.createSourceWithSsrc(456456L, new String[][]{}),
                        SourceUtil.createSourceWithSsrc(789789L, new String[][]{})

                }
        );
        sourceGroups.addSourceGroup("video", ssrcSimGroup);
        // Now create an RID group
        SourceGroup ridSimGroup = SourceUtil.createSourceGroup(
                SourceGroupPacketExtension.SEMANTICS_SIMULCAST,
                new SourcePacketExtension[] {
                        SourceUtil.createSourceWithRid("1", new String[][]{}),
                        SourceUtil.createSourceWithRid("2", new String[][]{}),
                        SourceUtil.createSourceWithRid("3", new String[][]{})
                }
        );

        assertFalse(sourceGroups.containsGroup("video", ridSimGroup));
    }
    /**
     * Basic test scenario for remove operation
     */
    @Test
    public void testRemoveSSRCGroup()
    {
        MediaSourceGroupMap ssrcGroups = new MediaSourceGroupMap();

        SourceGroup audioGroup1 = SourceUtil.createSourceGroup(
            SourceGroupPacketExtension.SEMANTICS_FID,
            new SourcePacketExtension[] {
                SourceUtil.createSourceWithSsrc(345646L, new String[][]{
                    {"cname", "bfdlkbmfdl"},
                    {"msid", "3425345-fgdh-54y45-hghfgh"}
                }),
                SourceUtil.createSourceWithSsrc(786587L, new String[][]{
                    {"cname", "vxpoivoiul"},
                    {"msid", "985-54y-55mgfg7-4-yh54"}
                })
            }
        );
        SourceGroup audioGroup2 = SourceUtil.createSourceGroup(
            SourceGroupPacketExtension.SEMANTICS_SIMULCAST,
            new SourcePacketExtension[] {
                SourceUtil.createSourceWithSsrc(2265675L, new String[][]{
                    {"cname", "89fd7g87dfbu"},
                    {"msid", "546-54-234-435-435"}
                }),
                SourceUtil.createSourceWithSsrc(3455667L, new String[][]{
                    {"cname", "hgj09j8gh0j8"},
                    {"msid", "657657-435-34534-5467"}
                }),
                SourceUtil.createSourceWithSsrc(8979879L, new String[][]{
                    {"cname", "7nb89m79bnm"},
                    {"msid", "4562-724575-54754-4527"}
                })
            }
        );
        SourceGroup audioGroup3 = SourceUtil.createSourceGroup(
            SourceGroupPacketExtension.SEMANTICS_SIMULCAST,
            new SourcePacketExtension[] {
                SourceUtil.createSourceWithSsrc(456457L, new String[][]{
                    {"cname", "vdfvdf8789df"},
                    {"msid", "56765-756756-34534"}
                }),
                SourceUtil.createSourceWithSsrc(234325L, new String[][]{
                    {"cname", "786dsf8g8dfg6"},
                    {"msid", "678678-56756-56756-65765"}
                }),
                SourceUtil.createSourceWithSsrc(879879L, new String[][]{
                    {"cname", "oilioli9io9"},
                    {"msid", "246-452645-425645-4526"}
                })
            }
        );

        ssrcGroups.getSourceGroupsForMedia("audio").add(audioGroup1);
        ssrcGroups.getSourceGroupsForMedia("audio").add(audioGroup2);
        ssrcGroups.getSourceGroupsForMedia("audio").add(audioGroup3);

        SourceGroup videoGroup1 = SourceUtil.createSourceGroup(
            SourceGroupPacketExtension.SEMANTICS_SIMULCAST,
            new SourcePacketExtension[] {
                SourceUtil.createSourceWithSsrc(2345346L, new String[][]{
                    {"cname", "342kj5hk3h5"},
                    {"msid", "476457-4355-456546-456"}
                }),
                SourceUtil.createSourceWithSsrc(768678L, new String[][]{
                    {"cname", "546lkjn45lk"},
                    {"msid", "245634-24536-2456-4526"}
                }),
                SourceUtil.createSourceWithSsrc(5646L, new String[][]{
                    {"cname", "32lk4j3l232"},
                    {"msid", "7654-5467-5647435-345"}
                }),
                SourceUtil.createSourceWithSsrc(2357561L, new String[][]{
                    {"cname", "kl65j7kl56jl"},
                    {"msid", "5747-4355-56723-34557"}
                })
            }
        );
        SourceGroup videoGroup2 = SourceUtil.createSourceGroup(
            SourceGroupPacketExtension.SEMANTICS_SIMULCAST,
            new SourcePacketExtension[] {
                SourceUtil.createSourceWithSsrc(8768678L, new String[][]{
                    {"cname", "fdg897g98"},
                    {"msid", "675768-678-678-678-65787"}
                }),
                SourceUtil.createSourceWithSsrc(3543556L, new String[][]{
                    {"cname", "5bv3n5b"},
                    {"msid", "6758-6786456-567856-86758"}
                }),
                SourceUtil.createSourceWithSsrc(5675634L, new String[][]{
                    {"cname", "67klm8lk768l"},
                    {"msid", "5436-54-6-45365437-567567"}
                })
            }
        );

        ssrcGroups.getSourceGroupsForMedia("video").add(videoGroup1);
        ssrcGroups.getSourceGroupsForMedia("video").add(videoGroup2);

        MediaSourceGroupMap toRemove = new MediaSourceGroupMap();
        toRemove.getSourceGroupsForMedia("audio").add(audioGroup3.copy());
        toRemove.getSourceGroupsForMedia("audio").add(audioGroup1.copy());
        toRemove.getSourceGroupsForMedia("video").add(videoGroup2.copy());

        /*toRemove.getSourceGroupsForMedia("audio").add(
            SourceUtil.createSourceGroup(
                SourceGroupPacketExtension.SEMANTICS_SIMULCAST,
                new SourcePacketExtension[]{
                    SourceUtil.createSourceWithSsrc(324234L, new String[][]{
                        {"cname", "dfggdf"},
                        {"msid", "bfgb88-323fgb-12gfgfh-fghy54"},
                    })
                })
        );*/

        MediaSourceGroupMap removed = ssrcGroups.remove(toRemove);

        compareGroupMaps(toRemove, removed);
    }

    private void compareGroupMaps(MediaSourceGroupMap g1, MediaSourceGroupMap g2)
    {
        // Compare as sets, because the order should not matter.
        Set<String> mediaTypes1 = new HashSet<>(g1.getMediaTypes());
        Set<String> mediaTypes2 = new HashSet<>(g2.getMediaTypes());

        assertEquals(mediaTypes1, mediaTypes2);

        for (String mediaType : mediaTypes1)
        {
            assertEquals(
                g1.getSourceGroupsForMedia(mediaType),
                g2.getSourceGroupsForMedia(mediaType));
        }
    }


}
