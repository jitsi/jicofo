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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * Tests for {@link Participant}.
 *
 * @author Pawel Domas
 */
@RunWith(JUnit4.class)
public class ParticipantTest
{
    /**
     * Tests the {@link Participant#addSSRCsFromContent(List)} which is normally
     * called either when the answer or the 'source-add' notification is
     * received.
     */
    // FIXME disabled until SSRC groups are not taken into account
    //@Test
    public void testAddSSRCFromContent()
    {
        int maxSSRCCount = 2;

        MockJitsiMeetConference mockConference = new MockJitsiMeetConference();

        MockMultiUserChat mockMultiUserChat
            = new MockMultiUserChat(null, null);

        MockRoomMember roomMember
            = mockMultiUserChat.createMockRoomMember("testMember");

        Participant participant
            = new Participant(mockConference, roomMember, maxSSRCCount);

        SourcePacketExtension[] audioSSRCs
            = new SourcePacketExtension[] {
            SSRCUtil.createSSRC(1L, new String[][]{
                {"cname", "cname1"},
                {"msid", "stream1 track1"}
            }),
            SSRCUtil.createSSRC(2L, new String[][]{
                {"cname", "cname2"},
                {"msid", "stream2 track2"}
            }),
            // Duplicated SSRC
            SSRCUtil.createSSRC(1L, new String[][]{
                {"cname", "cname3"},
                {"msid", "stream3 track3"}
            }),
            // Duplicated media stream id
            SSRCUtil.createSSRC(3L, new String[][]{
                {"cname", "cname2"},
                {"msid", "stream2 track2"}
            }),
            SSRCUtil.createSSRC(4L, new String[][]{
                {"cname", "cname4"},
                {"msid", "stream4 track4"}
            })};

        ContentPacketExtension audioContents
            = JingleOfferFactory.createAudioContent(false, true, true);

        RtpDescriptionPacketExtension audioRtpDescPe
            = JingleUtils.getRtpDescription(audioContents);

        for (SourcePacketExtension ssrc : audioSSRCs)
        {
            audioRtpDescPe.addChildExtension(ssrc);
        }

        List<ContentPacketExtension> answerContents = new ArrayList<>();

        answerContents.add(audioContents);

        MediaSSRCMap addedSSRCs
            = participant.addSSRCsFromContent(answerContents);

        List<SourcePacketExtension> addedAudioSSRCs
            = addedSSRCs.getSSRCsForMedia("audio");

        assertEquals(2, addedAudioSSRCs.size());

        SourcePacketExtension ssrc1 = addedSSRCs.findSSRC("audio", 1L);
        assertNotNull(ssrc1);
        assertEquals("cname1", ssrc1.getParameter("cname"));
        assertEquals("stream1 track1", ssrc1.getParameter("msid"));

        SourcePacketExtension ssrc2 = addedSSRCs.findSSRC("audio", 2L);
        assertNotNull(ssrc2);

        SourcePacketExtension ssrc3 = addedSSRCs.findSSRC("audio", 3L);
        assertNull(ssrc3); /* conflicting */

        SourcePacketExtension ssrc4 = addedSSRCs.findSSRC("audio", 4L);
        assertNull(ssrc4); /* overflows the max SSRC count */
    }
}
