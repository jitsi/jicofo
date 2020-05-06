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

import mock.*;
import mock.muc.*;

import mock.util.*;
import org.jitsi.xmpp.extensions.jingle.*;
import org.jitsi.xmpp.extensions.jitsimeet.*;

import org.jitsi.utils.logging.*;

import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;

import java.util.*;

import static org.junit.Assert.*;


/**
 *
 */
@RunWith(JUnit4.class)
public class BundleTest
{
    /**
     * The logger
     */
    private final static Logger logger = Logger.getLogger(BundleTest.class);

    static OSGiHandler osgi = OSGiHandler.getInstance();

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

    /**
     * Allocates Colibri channels in bundle
     */
    @Test
    public void testBundle()
        throws Exception
    {
        EntityBareJid roomName = JidCreate.entityBareFrom(
                "testroom@conference.pawel.jitsi.net");
        String serverName = "test-server";

        TestConference testConference
            = TestConference.allocate(osgi.bc, serverName, roomName);

        MockProtocolProvider pps
            = testConference.getFocusProtocolProvider();

        MockMultiUserChatOpSet mucOpSet = pps.getMockChatOpSet();

        MockMultiUserChat chat
            = (MockMultiUserChat) mucOpSet.findRoom(roomName.toString());

        MockParticipant user1 = new MockParticipant("user1");

        user1.join(chat);

        MockParticipant user2 = new MockParticipant("user2");

        user2.join(chat);

        JingleIQ user1Invite = user1.acceptInvite(6000)[0];

        validateSessionInit(user1Invite, true);

        // FIXME: this is not complete as we would have to validate candidates
        // sent and the best if also ICE negotiations state could be check if is
        // going to complete, but we need ICE transport manager for that.
        user1.generateFakeCandidates();
        JingleIQ user1Transport = user1.sendTransportInfo();
        assertNotNull(user1Transport);
        //logger.info("User1 transport info: " + user1Transport.toXML());

        JingleIQ user2Invite = user2.acceptInvite(4000)[0];
        validateSessionInit(user2Invite, true);

        user1.leave();
        user2.leave();

        testConference.stop();
    }

    static void validateSessionInit(JingleIQ sessionInit, boolean isBundle)
    {
        if (isBundle)
        {
            validateBundleGroup(sessionInit);
        }
        else
        {
            assertNull(
                sessionInit.getExtension(
                    GroupPacketExtension.ELEMENT_NAME,
                    GroupPacketExtension.NAMESPACE));
        }

        ContentPacketExtension firstContent
            = sessionInit.getContentList().get(0);

        for (ContentPacketExtension content : sessionInit.getContentList())
        {
            validateInitContent(content, firstContent, isBundle);
        }
    }

    static void validateInitContent(ContentPacketExtension content,
                                    ContentPacketExtension firstContent,
                                    boolean isBundle)
    {
        // We expect to find rtcp-mux if there is an RTP description
        RtpDescriptionPacketExtension rtpDesc
            = JingleUtils.getRtpDescription(content);
        if (rtpDesc != null)
        {
            if (isBundle)
            {
                assertNotNull(
                    rtpDesc.getFirstChildOfType(
                        RtcpmuxPacketExtension.class));
            }
            // else is optional
        }

        // FIXME: check transport is different if non bundle
        if (!isBundle)
            return;

        // Transport should be the same for each content
        if (content == firstContent)
            return;

        IceUdpTransportPacketExtension firstTransport
            = firstContent.getFirstChildOfType(
                    IceUdpTransportPacketExtension.class);

        IceUdpTransportPacketExtension transport
            = content.getFirstChildOfType(
                    IceUdpTransportPacketExtension.class);

        assertTransportTheSame(firstTransport, transport);
    }

    /**
     * FIXME: ID is not compared, but that's ok ?
     * @param a
     * @param b
     */
    static void assertTransportTheSame(IceUdpTransportPacketExtension a,
                                       IceUdpTransportPacketExtension b)
    {
        assertEquals(a.isRtcpMux(), b.isRtcpMux());

        assertEquals(a.getPassword(), b.getPassword());
        assertEquals(a.getUfrag(), b.getUfrag());

        for (CandidatePacketExtension toFind : a.getCandidateList())
        {
            findMatchingCandidateOrFail(b.getCandidateList(), toFind);
        }
    }

    static void findMatchingCandidateOrFail(
        List<CandidatePacketExtension> candidates,
        CandidatePacketExtension toFind)
    {
        for (CandidatePacketExtension toCheck : candidates)
        {
            boolean typeEq
                = toFind.getType().equals(toCheck.getType());

            boolean protoEq
                = Objects.equals(toFind.getProtocol(), toCheck.getProtocol());

            boolean ipEq = Objects.equals(toFind.getIP(), toCheck.getIP());

            boolean portEq = toFind.getPort() == toCheck.getPort();

            boolean relAddrEq
                    = Objects.equals(toFind.getRelAddr(), toCheck.getRelAddr());

            boolean relPortEq = toFind.getRelPort() == toCheck.getRelPort();

            boolean prioEq = toFind.getPriority() == toCheck.getPriority();

            boolean componentEq
                = toFind.getComponent() == toCheck.getComponent();

            boolean generationEq
                = toFind.getGeneration() == toCheck.getGeneration();

            boolean networkEq = toFind.getNetwork() == toCheck.getNetwork();

            boolean fundEq
                = Objects.equals(
                        toFind.getFoundation(), toCheck.getFoundation());

            if (typeEq && protoEq && ipEq && portEq
                && relAddrEq && relPortEq && prioEq
                && componentEq && generationEq && networkEq && fundEq)
            {
                return;
            }
        }

        fail("No candidate found for " + toFind.toXML());
    }

    static void validateBundleGroup(JingleIQ sessionInit)
    {
        GroupPacketExtension group
            = (GroupPacketExtension)
                    sessionInit.getExtension(
                            GroupPacketExtension.ELEMENT_NAME,
                            GroupPacketExtension.NAMESPACE);

        assertNotNull("No group extension in session init", group);

        assertEquals("Invalid group semantics",
                     GroupPacketExtension.SEMANTICS_BUNDLE,
                     group.getSemantics());

        List<ContentPacketExtension> groupContents = group.getContents();

        findContentByNameOrFail(groupContents, "audio");
        findContentByNameOrFail(groupContents, "video");
        findContentByNameOrFail(groupContents, "data");
    }

    static void findContentByNameOrFail(List<ContentPacketExtension> content,
                                         String name)
    {
        for (ContentPacketExtension cpe : content)
        {
            if (name.equals(cpe.getName()))
                return;
        }
        fail("No content found for name: " + name);
    }
}
