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

import org.jitsi.xmpp.extensions.colibri.*;
import org.jitsi.xmpp.extensions.jingle.*;

import org.jitsi.protocol.xmpp.util.*;
import org.jitsi.utils.logging.*;

import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;

import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;
import org.jxmpp.stringprep.*;

import java.util.*;

import static org.junit.Assert.*;

/**
 * Tests for {@link SSRCValidator}.
 *
 * @author Pawel Domas
 */
@RunWith(JUnit4.class)
public class SSRCValidatorTest
{
    static private Logger logger
        = Logger.getLogger(SSRCValidatorTest.class.getName());

    static OSGiHandler osgi = OSGiHandler.getInstance();

    private List<SourcePacketExtension> audioSources;

    private MediaSourceGroupMap groups;

    private MediaSourceMap sources;

    private List<SourceGroup> videoGroups;

    private List<SourcePacketExtension> videoSources;

    @BeforeClass
    public static void setUpClass()
        throws Exception
    {
        osgi.init();
    }

    @AfterClass
    public static void tearDownClass()
        throws Exception
    {
        osgi.shutdown();
    }

    @Before
    public void setUpSources()
            throws XmppStringprepException
    {
        sources = new MediaSourceMap();
        groups = new MediaSourceGroupMap();
        audioSources = sources.getSourcesForMedia("audio");
        videoSources = sources.getSourcesForMedia("video");
        videoGroups = groups.getSourceGroupsForMedia("video");
    }

    private void addDefaultAudioSSRCs()
    {
        audioSources.add(createSourceWithSsrc(1L));
        audioSources.add(createSourceWithSsrc(2L));
        audioSources.add(createSourceWithSsrc(4L));
    }

    private void addDefaultVideoSSRCs()
    {
        String cname = "videocname";
        String msid = "vstream vtrack";

        videoSources.add(createSSRC(10L, cname, msid));
        videoSources.add(createSSRC(20L, cname, msid));
        videoSources.add(createSSRC(30L, cname, msid));
        videoSources.add(createSSRC(40L, cname, msid));
        videoSources.add(createSSRC(50L, cname, msid));
        videoSources.add(createSSRC(60L, cname, msid));
    }

    private void addDefaultVideoGroups()
    {
        videoGroups.add(
            createGroup(
                SourceGroupPacketExtension.SEMANTICS_SIMULCAST,
                new long[] { 10L, 30L, 50L }));
        videoGroups.add(
            createGroup(
                SourceGroupPacketExtension.SEMANTICS_FID,
                new long[] { 10L, 20L }));
        videoGroups.add(
            createGroup(
                SourceGroupPacketExtension.SEMANTICS_FID,
                new long[] { 30L, 40L }));
        videoGroups.add(
            createGroup(
                SourceGroupPacketExtension.SEMANTICS_FID,
                new long[] { 50L, 60L }));
    }

    /**
     * This will add sources and groups to {@link #videoSources} and
     * {@link #videoGroups} which will consist of one 3 layered SIM group,
     * where each of those layers will consist of 2 RTX SSRCs (6 SSRC numbers
     * needed).
     *
     * @param cname the cname that will be used in the source description.
     * @param msid the msid that will be used in the source description.
     * @param videoSourcesArray an array of exactly 6 SSRC numbers.
     */
    private void addSimAndRtxParticipantVideoSources(
        String cname, String msid, long[] videoSourcesArray)
    {
        videoSources.add(createSSRC(videoSourcesArray[0], cname, msid));
        videoSources.add(createSSRC(videoSourcesArray[1], cname, msid));
        videoSources.add(createSSRC(videoSourcesArray[2], cname, msid));
        videoSources.add(createSSRC(videoSourcesArray[3], cname, msid));
        videoSources.add(createSSRC(videoSourcesArray[4], cname, msid));
        videoSources.add(createSSRC(videoSourcesArray[5], cname, msid));

        videoGroups.add(
            createGroup(
                SourceGroupPacketExtension.SEMANTICS_FID,
                new long[] { videoSourcesArray[0], videoSourcesArray[1] }));
        videoGroups.add(
            createGroup(
                SourceGroupPacketExtension.SEMANTICS_FID,
                new long[] { videoSourcesArray[2], videoSourcesArray[3] }));
        videoGroups.add(
            createGroup(
                SourceGroupPacketExtension.SEMANTICS_FID,
                new long[] { videoSourcesArray[4], videoSourcesArray[5] }));
        videoGroups.add(
            createGroup(
                SourceGroupPacketExtension.SEMANTICS_SIMULCAST,
                new long[]
                    {
                        videoSourcesArray[0],
                        videoSourcesArray[2],
                        videoSourcesArray[4]
                    }));
    }

    private SSRCValidator createValidator(int maxSourcesPerUser)
    {
        return new SSRCValidator(
                "someEndpointId",
                new MediaSourceMap(),
                new MediaSourceGroupMap(),
                maxSourcesPerUser,
                logger);
    }

    private SSRCValidator createValidator()
    {
        return createValidator(
                JitsiMeetGlobalConfig.getGlobalConfig(osgi.bc)
                    .getMaxSourcesPerUser());
    }

    @Test
    public void test2ParticipantsWithSimAndRtx()
        throws InvalidSSRCsException
    {
        addSimAndRtxParticipantVideoSources(
                "videocname",
                "vstream vtrack",
                new long[]
                    {
                        10L, 20L, 30L, 40L, 50L, 60L
                    });

        SSRCValidator validator = createValidator();
        validator.tryAddSourcesAndGroups(sources, groups);

        videoSources.clear();
        videoGroups.clear();
        addSimAndRtxParticipantVideoSources(
                "videocname2",
                "vstream2 vtrack2",
                new long[]
                    {
                        100L, 200L, 300L, 400L, 500L, 600L
                    });

        // Not creating new validator instance will make it remember previously
        // added sources and groups just like in the conference.
        validator.tryAddSourcesAndGroups(sources, groups);
    }

    @Test
    public void testNegative()
    {
        // ssrc=-1 *removes* the ssrc attribute
        // Create a ssrc=0, then hack it away. Invalid sources can only be
        // received over the wire, setSSRC clips invalid values.
        SourcePacketExtension sourceWithSsrc
                = createSourceWithSsrc(-1L);
        sourceWithSsrc.setAttribute(
                SourcePacketExtension.SSRC_ATTR_NAME,
                Long.toString(-1L));
        audioSources.add(sourceWithSsrc);

        this.addDefaultAudioSSRCs();

        try
        {
            createValidator().tryAddSourcesAndGroups(sources, groups);
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
        audioSources.add(createSourceWithSsrc(0));

        this.addDefaultAudioSSRCs();

        try
        {
            createValidator().tryAddSourcesAndGroups(sources, groups);
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
        //SourcePacketExtension ssrcInvalid = createSourceWithSsrc(0xFFFFFFFFFL);

        //this.addDefaultAudioSSRCs();

        // bla bla bla
    }

    @Test
    public void testDuplicate()
    {
        // Duplicated SSRC with 1
        SourcePacketExtension ssrc1Duplicate
            = SourceUtil.createSourceWithSsrc(1L, new String[][]{
                    {"cname", "cname3"},
                    {"msid", "stream3 track3"},
                    {"mslabel", "stream3"},
                    {"label", "track3"}
                });

        audioSources.add(ssrc1Duplicate);
        this.addDefaultAudioSSRCs();

        assertDuplicateDetected();
    }

    private void assertDuplicateDetected()
    {
        try
        {
            createValidator().tryAddSourcesAndGroups(sources, groups);
            fail("Did not detect SSRC 1 duplicate");
        }
        catch (InvalidSSRCsException exc)
        {
            // The same source was added as audio or video, but nothing defines
            // the order of addition of sources. So we expect either "audio" or
            // "video".
            String errorMsg = exc.getMessage();
            errorMsg = errorMsg.replaceAll("audio", "XXXXX");
            errorMsg = errorMsg.replaceAll("video", "XXXXX");
            assertEquals(
                "Invalid message (constant needs update ?): " + errorMsg,
                "Source ssrc=1 is in XXXXX already", errorMsg);
        }
    }

    @Test
    public void testDuplicateDifferentMediaType()
    {
        // Duplicated video SSRC will conflict with SSRC 1 in audio
        SourcePacketExtension ssrc1Duplicate
            = SourceUtil.createSourceWithSsrc(1L, new String[][]{
            {"cname", "cname3"},
            {"msid", "stream3 track3"},
            {"mslabel", "stream3"},
            {"label", "track3"}
        });

        videoSources.add(ssrc1Duplicate);

        this.addDefaultAudioSSRCs();
        this.addDefaultVideoSSRCs();
        this.addDefaultVideoGroups();

        assertDuplicateDetected();
    }

    @Test
    public void testMSIDDuplicate()
    {
        // Duplicated media stream id with SSRC2
        SourcePacketExtension ssrc3
            = SourceUtil.createSourceWithSsrc(3L, new String[][]{
            {"cname", "cname2"},
            {"msid", "stream2 track2"},
            {"mslabel", "stream2"},
            {"label", "track2"}
        });

        this.addDefaultAudioSSRCs();

        audioSources.add(ssrc3);

        try
        {
            createValidator().tryAddSourcesAndGroups(sources, groups);
            fail("Did not detect MSID duplicate");
        }
        catch (InvalidSSRCsException exc)
        {
            assertEquals(
                "Not grouped SSRC 3 has conflicting"
                    + " MSID 'stream2 track2' with 2",
                exc.getMessage());
        }
    }



    @Test
    public void testMSIDMismatchInTheSameGroup()
    {
        this.addDefaultVideoSSRCs();
        this.addDefaultVideoGroups();

        // Overwrite SSRC 20 with something wrong
        videoSources.remove(1);
        videoSources.add(createSSRC(20L, "blabla", "wrongStream wrongTrack"));

        try
        {
            createValidator().tryAddSourcesAndGroups(sources, groups);
            fail("Did not detect MSID mismatch in 10+20 FID group");
        }
        catch (InvalidSSRCsException exc)
        {
            String errorMsg = exc.getMessage();
            assertTrue(
                "Invalid message (constant needs update ?): " + errorMsg,
                errorMsg.startsWith(
                    "MSID mismatch detected "
                        + "in group SourceGroup[FID, ssrc=10, ssrc=20, ]"));
        }
    }

    @Test
    public void testMsidConflictFidGroups()
    {
        String cname = "videocname";
        String msid = "vstream vtrack";

        videoSources.add(createSSRC(10L, cname, msid));
        videoSources.add(createSSRC(20L, cname, msid));
        videoSources.add(createSSRC(30L, cname, msid));
        videoSources.add(createSSRC(40L, cname, msid));

        videoGroups.add(
            createGroup(
                    SourceGroupPacketExtension.SEMANTICS_FID,
                    new long[] { 10L, 20L }));
        videoGroups.add(
            createGroup(
                    SourceGroupPacketExtension.SEMANTICS_FID,
                    new long[] { 30L, 40L }));

        try
        {
            createValidator().tryAddSourcesAndGroups(sources, groups);
            fail("Did not detect 2 FID groups for the same MSID");
        }
        catch (InvalidSSRCsException exc)
        {
            String errorMsg = exc.getMessage();
            assertTrue(
                "Invalid message (constant needs update ?): " + errorMsg,
                errorMsg.startsWith(
                    "MSID conflict across FID groups: vstream vtrack,"
                        + " SourceGroup[FID, ssrc=30, ssrc=40, ]@")
                    && errorMsg.contains(
                        " conflicts with group SourceGroup"
                            + "[FID, ssrc=10, ssrc=20, ]@"));
        }
    }

    @Test
    public void testMsidMismatchInSimGroup()
    {
        String cname = "videocname";
        String msid = "vstream vtrack";

        videoSources.add(createSSRC(10L, cname, msid));
        videoSources.add(createSSRC(20L, cname, msid));
        videoSources.add(createSSRC(30L, cname, msid));
        videoSources.add(createSSRC(40L, cname, msid + "224"));

        videoGroups.add(
            createGroup(
                SourceGroupPacketExtension.SEMANTICS_SIMULCAST,
                new long[] { 10L, 30L }));
        videoGroups.add(
            createGroup(
                SourceGroupPacketExtension.SEMANTICS_FID,
                new long[] { 10L, 20L }));
        videoGroups.add(
            createGroup(
                SourceGroupPacketExtension.SEMANTICS_FID,
                new long[] { 30L, 40L }));

        try
        {
            createValidator().tryAddSourcesAndGroups(sources, groups);
            fail("Did not detect MSID mismatch in SIM group");
        }
        catch (InvalidSSRCsException exc)
        {
            String errorMsg = exc.getMessage();
            assertTrue(
                "Invalid message (constant needs update ?): " + errorMsg,
                errorMsg.startsWith(
                    "MSID mismatch detected in group "
                        + "SourceGroup[FID, ssrc=30, ssrc=40, ]"));
        }
    }

    @Test
    public void testMsidConflictSimGroups()
    {
        String cname = "videocname";
        String msid = "vstream vtrack";

        videoSources.add(createSSRC(10L, cname, msid));
        videoSources.add(createSSRC(20L, cname, msid));
        videoSources.add(createSSRC(30L, cname, msid));
        videoSources.add(createSSRC(40L, cname, msid));

        videoGroups.add(
            createGroup(
                SourceGroupPacketExtension.SEMANTICS_SIMULCAST,
                new long[] { 10L, 20L }));
        videoGroups.add(
            createGroup(
                SourceGroupPacketExtension.SEMANTICS_SIMULCAST,
                new long[] { 30L, 40L }));

        try
        {
            createValidator().tryAddSourcesAndGroups(sources, groups);
            fail("Did not detect MSID conflict in SIM groups");
        }
        catch (InvalidSSRCsException exc)
        {
            String errorMsg = exc.getMessage();
            assertTrue(
                "Invalid message (constant needs update ?): " + errorMsg,
                errorMsg.startsWith(
                    "MSID conflict across SIM groups: vstream vtrack, ssrc=30"
                        + " conflicts with group Simulcast[ssrc=10,ssrc=20,]"));
        }
    }

    @Test
    public void testNoMsidSimGroup()
    {
        String cname = "videocname";

        videoSources.add(createSSRC(10L, cname, null));
        videoSources.add(createSSRC(20L, cname, null));
        videoSources.add(createSSRC(30L, cname, null));

        videoGroups.add(
            createGroup(
                SourceGroupPacketExtension.SEMANTICS_SIMULCAST,
                new long[] { 10L, 20L, 30L }));

        try
        {
            createValidator().tryAddSourcesAndGroups(sources, groups);
            fail("Did not detect 'null' MSID in SIM group");
        }
        catch (InvalidSSRCsException exc)
        {
            String errorMsg = exc.getMessage();
            assertTrue(
                    "Invalid message (constant needs update ?): " + errorMsg,
                    errorMsg.startsWith(
                            "Grouped ssrc=10 has no 'msid'"));
        }
    }

    @Test
    public void testTrackMismatchInTheSameGroup()
    {
        this.addDefaultVideoSSRCs();
        this.addDefaultVideoGroups();

        // Overwrite SSRC 20 with wrong track id part of the MSID
        videoSources.remove(1);
        videoSources.add(createSSRC(20L, "videocname", "vstream wrongTrack"));

        try
        {
            createValidator().tryAddSourcesAndGroups(sources, groups);
            fail("Did not detect track mismatch in 10+20 FID group");
        }
        catch (InvalidSSRCsException exc)
        {
            String errorMsg = exc.getMessage();
            assertTrue(
                "Invalid message (constant needs update ?): " + errorMsg,
                errorMsg.startsWith(
                    "MSID mismatch detected "
                        + "in group SourceGroup[FID, ssrc=10, ssrc=20, ]"));
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

        this.addDefaultAudioSSRCs();
        audioSources.add(createSourceWithSsrc(5L));
        audioSources.add(createSourceWithSsrc(6L));

        SSRCValidator ssrcValidator = createValidator(maxSsrcCount);

        Object[] ssrcsAndGroups
            = ssrcValidator.tryAddSourcesAndGroups(sources, groups);
        MediaSourceMap addedSSRCs = (MediaSourceMap) ssrcsAndGroups[0];

        List<SourcePacketExtension> addedAudioSSRCs
            = addedSSRCs.getSourcesForMedia("audio");

        assertEquals(4, addedAudioSSRCs.size());

        verifySSRC(
                "cname5",
                "stream5 track5",
                addedSSRCs.findSourceViaSsrc("audio", 5L));

        /* overflows the max Source count */
        assertNull(addedSSRCs.findSourceViaSsrc("audio", 6L));

        // Now try to add Sources with owner
        sources = new MediaSourceMap();
        groups = new MediaSourceGroupMap();

        String owner = "user@server.com/blabla";
        List<SourcePacketExtension> audioSources
            = sources.getSourcesForMedia("audio");

        audioSources.add(createSourceWithSsrc(10L, owner));
        audioSources.add(createSourceWithSsrc(11L, owner));
        audioSources.add(createSourceWithSsrc(12L, owner));
        audioSources.add(createSourceWithSsrc(13L, owner));
        audioSources.add(createSourceWithSsrc(14L, owner));
        audioSources.add(createSourceWithSsrc(15L, owner));

        ssrcsAndGroups
            = ssrcValidator.tryAddSourcesAndGroups(sources, groups);
        addedSSRCs = (MediaSourceMap) ssrcsAndGroups[0];

        assertEquals(
                maxSsrcCount, addedSSRCs.getSourcesForMedia("audio").size());
    }

    @Test
    public void testParamFilter()
        throws InvalidSSRCsException
    {
        this.addDefaultAudioSSRCs();

        Object[] ssrcsAndGroups
            = createValidator().tryAddSourcesAndGroups(sources, groups);
        MediaSourceMap addedSSRCs = (MediaSourceMap) ssrcsAndGroups[0];

        List<SourcePacketExtension> addedAudioSSRCs
            = addedSSRCs.getSourcesForMedia("audio");

        assertEquals(3, addedAudioSSRCs.size());

        verifySSRC(
            "cname1", "stream1 track1", addedSSRCs.findSourceViaSsrc("audio", 1L));

        verifySSRC(
            "cname2", "stream2 track2", addedSSRCs.findSourceViaSsrc("audio", 2L));

        verifySSRC(
            "cname4", "stream4 track4", addedSSRCs.findSourceViaSsrc("audio", 4L));
    }

    @Test
    public void testEmptyGroup()
        throws InvalidSSRCsException
    {
        this.addDefaultVideoSSRCs();
        this.addDefaultVideoGroups();

        int defaultVideoGroupSize = videoGroups.size();

        SourceGroupPacketExtension group = new SourceGroupPacketExtension();
        group.setSemantics(SourceGroupPacketExtension.SEMANTICS_FID);
        videoGroups.add(new SourceGroup(group));

        SourceGroupPacketExtension group2 = new SourceGroupPacketExtension();
        group2.setSemantics(SourceGroupPacketExtension.SEMANTICS_FID);
        videoGroups.add(new SourceGroup(group2));

        MediaSourceGroupMap addedGroups
            = (MediaSourceGroupMap) createValidator()
                .tryAddSourcesAndGroups(sources, groups)[1];

        assertEquals(
            defaultVideoGroupSize,
            addedGroups.getSourceGroupsForMedia("video").size());
    }

    @Test
    public void testGroupedSSRCNotFound()
    {
        videoSources.add(createSSRC(1L, "cname1", "s t1"));

        videoGroups.add(
            createGroup(
                    SourceGroupPacketExtension.SEMANTICS_FID,
                    new long[] { 1L, 2L}));

        try
        {
            createValidator().tryAddSourcesAndGroups(sources, groups);
            fail("Failed to detect that SSRC 2 is not in video SDP");
        }
        catch (InvalidSSRCsException e)
        {
            String errorMsg = e.getMessage();
            assertTrue(
                    "Invalid message (constant needs update ?): " + errorMsg,
                    errorMsg.startsWith(
                        "Source ssrc=2 not found in 'video' for group:"
                            + " SourceGroup[FID, ssrc=1, ssrc=2, ]"));
        }
    }

    @Test
    public void testDuplicatedGroups()
        throws InvalidSSRCsException
    {
        videoSources.add(createSSRC(1L, "cname1", "s t1"));
        videoSources.add(createSSRC(2L, "cname1", "s t1"));

        videoGroups.add(
            createGroup(
                    SourceGroupPacketExtension.SEMANTICS_FID,
                    new long[] { 1L, 2L}));

        videoGroups.add(
            createGroup(
                    SourceGroupPacketExtension.SEMANTICS_FID,
                    new long[] { 1L, 2L}));

        Object[] ssrcsAndGroups
            = createValidator().tryAddSourcesAndGroups(sources, groups);

        MediaSourceMap ssrcs = (MediaSourceMap) ssrcsAndGroups[0];
        assertEquals(2, ssrcs.getSourcesForMedia("video").size());

        MediaSourceGroupMap groups = (MediaSourceGroupMap) ssrcsAndGroups[1];
        assertEquals(1, groups.getSourceGroupsForMedia("video").size());
    }

    @Test
    public void testStateBrokenByRemoval()
            throws InvalidSSRCsException
    {
        addDefaultVideoSSRCs();
        addDefaultVideoGroups();

        SSRCValidator ssrcValidator = createValidator();

        ssrcValidator.tryAddSourcesAndGroups(sources, groups);

        MediaSourceMap sourcesToRemove = new MediaSourceMap();
        // remove SSRCs 10 and 20
        sourcesToRemove.addSource("video", videoSources.get(0).copy());
        sourcesToRemove.addSource("video", videoSources.get(1).copy());

        MediaSourceGroupMap groupsToRemove = new MediaSourceGroupMap();
        // remove SIM 10 30 50 group
        groupsToRemove.addSourceGroup("video", videoGroups.get(0).copy());
        // remove FIR 10 20 group
        groupsToRemove.addSourceGroup("video", videoGroups.get(1).copy());

        try {
            ssrcValidator.tryRemoveSourcesAndGroups(sourcesToRemove, groupsToRemove);

            fail("Did not detect broken state after SIM and FIR groups removal");
        } catch (InvalidSSRCsException exception) {
            String msg = exception.getMessage();
            String msgPattern
                = "MSID conflict across FID groups: vstream vtrack, "
                    + "SourceGroup\\[FID, ssrc=50, ssrc=60, ]@\\w+ conflicts "
                    + "with group SourceGroup\\[FID, ssrc=30, ssrc=40, ]@\\w+";

            if(!msg.matches(msgPattern)) {
                fail("Fail error msg validation: " + msg);
            }
        }
    }

    @Test
    public void testStateBrokenBySourceRemoval()
            throws InvalidSSRCsException
    {
        addDefaultVideoSSRCs();
        addDefaultVideoGroups();

        SSRCValidator ssrcValidator = createValidator();

        ssrcValidator.tryAddSourcesAndGroups(sources, groups);

        MediaSourceMap sourcesToRemove = new MediaSourceMap();
        // remove SSRCs 10
        sourcesToRemove.addSource("video", videoSources.get(0).copy());

        try {
            ssrcValidator.tryRemoveSourcesAndGroups(sourcesToRemove, new MediaSourceGroupMap());

            fail("Did not detect broken state after source removal");
        } catch (InvalidSSRCsException exception) {
            String msg = exception.getMessage();
            String msgPattern
                = "Source ssrc=10 not found in 'video' for group: "
                    + "SourceGroup\\[SIM, ssrc=10, ssrc=30, ssrc=50, ]@\\w+";

            if(!msg.matches(msgPattern)) {
                fail("Fail error msg validation: " + msg);
            }
        }
    }

    @Test
    public void testStateBrokenByGroupRemoval()
            throws InvalidSSRCsException
    {
        addDefaultVideoSSRCs();
        addDefaultVideoGroups();

        SSRCValidator ssrcValidator = createValidator();

        ssrcValidator.tryAddSourcesAndGroups(sources, groups);

        MediaSourceMap sourcesToRemove = new MediaSourceMap();
        MediaSourceGroupMap groupsToRemove = new MediaSourceGroupMap();

        // remove SIM 10 30 50 group
        groupsToRemove.addSourceGroup("video", videoGroups.get(0).copy());

        try {
            ssrcValidator.tryRemoveSourcesAndGroups(sourcesToRemove, groupsToRemove);

            fail("Did not detect broken state after SIM group removal");
        } catch (InvalidSSRCsException exception) {
            String msg = exception.getMessage();
            String msgPattern
                = "MSID conflict across FID groups: vstream vtrack, "
                    + "SourceGroup\\[FID, ssrc=30, ssrc=40, ]@\\w+ "
                    + "conflicts with group "
                    + "SourceGroup\\[FID, ssrc=10, ssrc=20, ]@\\w+";

            if(!msg.matches(msgPattern)) {
                fail("Fail error msg validation: " + msg);
            }
        }
    }

    @Test
    public void testStateBrokenByFirGroupRemoval()
            throws InvalidSSRCsException
    {
        addDefaultVideoSSRCs();
        addDefaultVideoGroups();

        SSRCValidator ssrcValidator = createValidator();

        ssrcValidator.tryAddSourcesAndGroups(sources, groups);

        MediaSourceMap sourcesToRemove = new MediaSourceMap();
        MediaSourceGroupMap groupsToRemove = new MediaSourceGroupMap();
        // remove FIR 10 20 group
        groupsToRemove.addSourceGroup("video", videoGroups.get(1).copy());

        try {
            ssrcValidator.tryRemoveSourcesAndGroups(sourcesToRemove, groupsToRemove);

            fail("Did not detect broken state after FIR group removal");
        } catch (InvalidSSRCsException exception) {
            String msg = exception.getMessage();
            String msgPattern
                = "SIM group size != FID group count: SourceGroup"
                    + "\\[SIM, ssrc=10, ssrc=30, ssrc=50, ]@\\w+ != 2";

            if(!msg.matches(msgPattern)) {
                fail("Fail error msg validation: " + msg);
            }
        }
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

    private SourcePacketExtension createSourceWithSsrc(long ssrcNum)
    {
        return SourceUtil.createSourceWithSsrc(ssrcNum, new String[][]{
            {"cname", "cname" + ssrcNum},
            {"msid", "stream" + ssrcNum + " track" + ssrcNum},
            {"mslabel", "stream" + ssrcNum},
            {"label", "track" + ssrcNum}
        });
    }

    private SourcePacketExtension createSourceWithSsrc(
            long ssrcNum, String owner)
    {
        SourcePacketExtension source = createSourceWithSsrc(ssrcNum);

        try
        {
            Jid ownerJid = JidCreate.from(owner);

            SSRCSignaling.setSSRCOwner(source, ownerJid);

            return source;
        }
        catch (XmppStringprepException var2)
        {
            throw new IllegalArgumentException("Invalid owner", var2);
        }
    }

    private SourcePacketExtension createSSRC(long ssrcNum, String cname, String msid)
    {
        return SourceUtil.createSourceWithSsrc(ssrcNum, new String[][]{
            {"cname", cname},
            {"msid", msid}
        });
    }

    private SourceGroup createGroup(String semantics, long[] ssrcs)
    {
        SourceGroupPacketExtension groupPe = new SourceGroupPacketExtension();

        groupPe.setSemantics(semantics);

        List<SourcePacketExtension> ssrcList = new ArrayList<>(ssrcs.length);
        for (long ssrc : ssrcs)
        {
            ssrcList.add(
                    SourceUtil.createSourceWithSsrc(
                            ssrc,
                            new String[][]{ }));
        }
        groupPe.addSources(ssrcList);

        return new SourceGroup(groupPe);
    }
}
