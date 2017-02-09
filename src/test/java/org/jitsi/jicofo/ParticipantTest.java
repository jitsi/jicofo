/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2016 Atlassian Pty Ltd
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

import mock.muc.*;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import net.java.sip.communicator.impl.protocol.jabber.jinglesdp.*;

import org.jitsi.jicofo.util.*;
import org.jitsi.protocol.xmpp.util.*;

import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;

import java.util.*;

import static org.junit.Assert.*;

/**
 * Tests for {@link Participant}.
 *
 * @author Pawel Domas
 */
@RunWith(JUnit4.class)
public class ParticipantTest
{
    private MockJitsiMeetConference mockConference;
    private MockRoomMember roomMember;
    private Participant participant;

    private ArrayList<ContentPacketExtension> answerContents;
    private RtpDescriptionPacketExtension audioRtpDescPe;
    private RtpDescriptionPacketExtension videoRtpDescPe;

    private SourcePacketExtension[] audioSSRCs;

    private SourcePacketExtension[] videoSSRCs;
    private SourceGroupPacketExtension[] videoGroups;

    @Before
    public void setUpContents()
    {
        this.mockConference = new MockJitsiMeetConference();
        MockMultiUserChat mockMultiUserChat = new MockMultiUserChat(null, null);
        this.roomMember = mockMultiUserChat.createMockRoomMember("testMember");
        this.participant
            = new Participant(mockConference, roomMember, 20);

        SourcePacketExtension ssrc1 = createSSRC(1L);
        SourcePacketExtension ssrc2 = createSSRC(2L);
        SourcePacketExtension ssrc4 = createSSRC(4L);

        this.audioSSRCs = new SourcePacketExtension[] { ssrc1, ssrc2, ssrc4};

        String cname = "videocname";
        String msid = "vstream vtrack";

        this.videoSSRCs = new SourcePacketExtension[] {
            createGroupSSRC(10L, cname, msid),
            createGroupSSRC(20L, cname, msid),
            createGroupSSRC(30L, cname, msid),
            createGroupSSRC(40L, cname, msid),
            createGroupSSRC(50L, cname, msid),
            createGroupSSRC(60L, cname, msid)
        };

        this.videoGroups = new SourceGroupPacketExtension[] {
            createGroup(
                SourceGroupPacketExtension.SEMANTICS_SIMULCAST,
                new long[] { 10L, 30L, 50L },
                cname, msid),
            createGroup(
                SourceGroupPacketExtension.SEMANTICS_FID,
                new long[] { 10L, 20L }, cname, msid),
            createGroup(
                SourceGroupPacketExtension.SEMANTICS_FID,
                new long[] { 30L, 40L }, cname, msid),
            createGroup(
                SourceGroupPacketExtension.SEMANTICS_FID,
                new long[] { 50L, 60L }, cname, msid)
        };

        ContentPacketExtension audioContents
            = JingleOfferFactory.createAudioContent(false, true, true);

        ContentPacketExtension videoContents
            = JingleOfferFactory.createVideoContent(false, true, true, 0, 100);

        this.audioRtpDescPe = JingleUtils.getRtpDescription(audioContents);
        this.videoRtpDescPe = JingleUtils.getRtpDescription(videoContents);

        this.answerContents = new ArrayList<>();

        answerContents.add(audioContents);
        answerContents.add(videoContents);
    }

    private void addDefaultAudioSSRCs()
    {
        for (SourcePacketExtension ssrc : audioSSRCs)
        {
            audioRtpDescPe.addChildExtension(ssrc);
        }
    }

    private void addDefaultVideoSSRCs()
    {
        for (SourcePacketExtension ssrc : videoSSRCs)
            videoRtpDescPe.addChildExtension(ssrc);
    }

    private void addDefaultVideoGroups()
    {
        for (SourceGroupPacketExtension group : videoGroups)
            videoRtpDescPe.addChildExtension(group);
    }

    @Test
    public void testNegative()
    {
        audioRtpDescPe.addChildExtension(createSSRC(-1));

        this.addDefaultAudioSSRCs();

        try
        {
            participant.addSSRCsAndGroupsFromContent(answerContents);
            fail("Did not detect SSRC -1 as invalid");
        }
        catch (InvalidSSRCsException exc)
        {
            assertEquals("Illegal SSRC value: -1", exc.getMessage());
        }
    }

    @Test
    public void testZero()
    {
        audioRtpDescPe.addChildExtension(createSSRC(0));

        this.addDefaultAudioSSRCs();

        try
        {
            participant.addSSRCsAndGroupsFromContent(answerContents);
            fail("Did not detect SSRC 0 as invalid");
        }
        catch (InvalidSSRCsException exc)
        {
            assertEquals("Illegal SSRC value: 0", exc.getMessage());
        }
    }

    //@Test
    public void testInvalidNumber()
    {
        // FIXME SSRC implementation will always trim the value to 32bit
        //SourcePacketExtension ssrcInvalid = createSSRC(0xFFFFFFFFFL);

        //this.addDefaultAudioSSRCs();

        // bla bla bla
    }

    @Test
    public void testDuplicate()
    {
        // Duplicated SSRC with 1
        SourcePacketExtension ssrc1Duplicate
            = SSRCUtil.createSSRC(1L, new String[][]{
                    {"cname", "cname3"},
                    {"msid", "stream3 track3"},
                    {"mslabel", "stream3"},
                    {"label", "track3"}
                });

        audioRtpDescPe.addChildExtension(ssrc1Duplicate);

        this.addDefaultAudioSSRCs();

        try
        {
            participant.addSSRCsAndGroupsFromContent(answerContents);
            fail("Did not detect SSRC 1 duplicate");
        }
        catch (InvalidSSRCsException exc)
        {
            assertEquals("SSRC 1 is in audio already", exc.getMessage());
        }
    }

    @Test
    public void testDuplicateDifferentMediaType()
    {
        // Duplicated video SSRC will conflict with SSRC 1 in audio
        SourcePacketExtension ssrc1Duplicate
            = SSRCUtil.createSSRC(1L, new String[][]{
            {"cname", "cname3"},
            {"msid", "stream3 track3"},
            {"mslabel", "stream3"},
            {"label", "track3"}
        });

        videoRtpDescPe.addChildExtension(ssrc1Duplicate);

        this.addDefaultAudioSSRCs();
        this.addDefaultVideoSSRCs();
        this.addDefaultVideoGroups();

        try
        {
            participant.addSSRCsAndGroupsFromContent(answerContents);
            fail("Did not detect SSRC 1 duplicate");
        }
        catch (InvalidSSRCsException exc)
        {
            assertEquals("SSRC 1 is in audio already", exc.getMessage());
        }
    }

    @Test
    public void testMSIDDuplicate()
    {
        // Duplicated media stream id with SSRC2
        SourcePacketExtension ssrc3
            = SSRCUtil.createSSRC(3L, new String[][]{
            {"cname", "cname2"},
            {"msid", "stream2 track2"},
            {"mslabel", "stream2"},
            {"label", "track2"}
        });

        this.addDefaultAudioSSRCs();

        audioRtpDescPe.addChildExtension(ssrc3);

        try
        {
            participant.addSSRCsAndGroupsFromContent(answerContents);
            fail("Did not detect MSID duplicate");
        }
        catch (InvalidSSRCsException exc)
        {
            assertEquals(
                "Not grouped SSRC 3 has conflicting MSID 'stream2' with 2",
                exc.getMessage());
        }
    }

    /**
     * Tests the max SSRC count limit.
     */
    @Test
    public void testSSRCLimit()
        throws InvalidSSRCsException
    {
        int maxSsrcCount = 4;
        this.participant
            = new Participant(mockConference, roomMember, maxSsrcCount);

        this.addDefaultAudioSSRCs();
        audioRtpDescPe.addChildExtension(createSSRC(5L));
        audioRtpDescPe.addChildExtension(createSSRC(6L));

        Object[] ssrcsAndGroups
            = participant.addSSRCsAndGroupsFromContent(answerContents);
        MediaSSRCMap addedSSRCs = (MediaSSRCMap) ssrcsAndGroups[0];

        List<SourcePacketExtension> addedAudioSSRCs
            = addedSSRCs.getSSRCsForMedia("audio");

        assertEquals(4, addedAudioSSRCs.size());

        verifySSRC(
            "cname5", "stream5 track5", addedSSRCs.findSSRC("audio", 5L));

        /* overflows the max SSRC count */
        assertNull(addedSSRCs.findSSRC("audio", 6L));
    }

    @Test
    public void testParamFilter()
        throws InvalidSSRCsException
    {
        this.addDefaultAudioSSRCs();

        Object[] ssrcsAndGroups
            = participant.addSSRCsAndGroupsFromContent(answerContents);
        MediaSSRCMap addedSSRCs = (MediaSSRCMap) ssrcsAndGroups[0];

        List<SourcePacketExtension> addedAudioSSRCs
            = addedSSRCs.getSSRCsForMedia("audio");

        assertEquals(3, addedAudioSSRCs.size());

        verifySSRC(
            "cname1", "stream1 track1", addedSSRCs.findSSRC("audio", 1L));

        verifySSRC(
            "cname2", "stream2 track2", addedSSRCs.findSSRC("audio", 2L));

        verifySSRC(
            "cname4", "stream4 track4", addedSSRCs.findSSRC("audio", 4L));
    }

    @Test
    public void testEmptyGroup()
        throws InvalidSSRCsException
    {
        this.addDefaultVideoSSRCs();
        this.addDefaultVideoGroups();

        SourceGroupPacketExtension group = new SourceGroupPacketExtension();
        group.setSemantics(SourceGroupPacketExtension.SEMANTICS_FID);
        videoRtpDescPe.addChildExtension(group);

        SourceGroupPacketExtension group2 = new SourceGroupPacketExtension();
        group2.setSemantics(SourceGroupPacketExtension.SEMANTICS_FID);
        videoRtpDescPe.addChildExtension(group2);

        MediaSSRCGroupMap addedGroups
            = (MediaSSRCGroupMap) participant
                .addSSRCsAndGroupsFromContent(answerContents)[1];

        assertEquals(
            this.videoGroups.length,
            addedGroups.getSSRCGroupsForMedia("video").size());
    }

    @Test
    public void testGroupedSSRCNotFound()
    {
        SourcePacketExtension ssrc1 = createGroupSSRC(1L, "cname1", "s t1");

        SourceGroupPacketExtension group1
            = createGroup(
                SourceGroupPacketExtension.SEMANTICS_FID,
                new long[] { 1L, 2L}, "cname1", "s t1");

        videoRtpDescPe.addChildExtension(ssrc1);
        videoRtpDescPe.addChildExtension(group1);

        try
        {
            participant.addSSRCsAndGroupsFromContent(answerContents);
            fail("Failed to detect that SSRC 2 is not in video SDP");
        }
        catch (InvalidSSRCsException e)
        {
            assertTrue(e.getMessage().startsWith(
                "SSRC 2 not found in video for group: SSRCGroup[FID, 1, 2, ]"));
        }
    }

    @Test
    public void testDuplicatedGroups()
        throws InvalidSSRCsException
    {
        SourcePacketExtension ssrc1 = createGroupSSRC(1L, "cname1", "s t1");
        SourcePacketExtension ssrc2 = createGroupSSRC(2L, "cname1", "s t1");

        SourceGroupPacketExtension group1
            = createGroup(
                    SourceGroupPacketExtension.SEMANTICS_FID,
                    new long[] { 1L, 2L}, "cname1", "s t1");

        videoRtpDescPe.addChildExtension(ssrc1);
        videoRtpDescPe.addChildExtension(ssrc2);
        videoRtpDescPe.addChildExtension(group1);

        videoRtpDescPe.addChildExtension(
            createGroup(
                SourceGroupPacketExtension.SEMANTICS_FID,
                new long[] { 1L, 2L}, "cname1", "s t1"));

        Object[] ssrcsAndGroups
            = participant.addSSRCsAndGroupsFromContent(answerContents);

        MediaSSRCMap ssrcs = (MediaSSRCMap) ssrcsAndGroups[0];
        assertEquals(2, ssrcs.getSSRCsForMedia("video").size());

        MediaSSRCGroupMap groups = (MediaSSRCGroupMap) ssrcsAndGroups[1];
        assertEquals(1, groups.getSSRCGroupsForMedia("video").size());
    }

    private void verifySSRC(
        String cname, String msid, SourcePacketExtension ssrc)
    {
        assertNotNull(ssrc);
        assertEquals(cname, ssrc.getParameter("cname"));
        assertEquals(msid, ssrc.getParameter("msid"));
        assertNull(ssrc.getParameter("mslabel"));
        assertNull(ssrc.getParameter("label"));
    }

    private SourcePacketExtension createSSRC(long ssrcNum)
    {
        return SSRCUtil.createSSRC(ssrcNum, new String[][]{
            {"cname", "cname" + ssrcNum},
            {"msid", "stream" + ssrcNum + " track" + ssrcNum},
            {"mslabel", "stream" + ssrcNum},
            {"label", "track" + ssrcNum}
        });
    }

    private SourcePacketExtension createGroupSSRC(long ssrcNum, String cname, String msid)
    {
        return SSRCUtil.createSSRC(ssrcNum, new String[][]{
            {"cname", cname},
            {"msid", msid}
        });
    }

    private SourceGroupPacketExtension createGroup(String semantics,
                                                   long[] ssrcs,
                                                   String cname,
                                                   String msid)
    {
        SourceGroupPacketExtension groupPe = new SourceGroupPacketExtension();

        groupPe.setSemantics(semantics);

        List<SourcePacketExtension> ssrcList = new ArrayList<>(ssrcs.length);
        for (long ssrc : ssrcs)
        {
            ssrcList.add(createGroupSSRC(ssrc, cname, msid));
        }
        groupPe.addSources(ssrcList);

        return groupPe;
    }
}
