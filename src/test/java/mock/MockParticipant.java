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
package mock;

import mock.muc.*;
import mock.util.*;
import mock.xmpp.*;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jitsimeet.*;
import net.java.sip.communicator.util.*;

import org.jitsi.impl.protocol.xmpp.*;
import org.jitsi.jicofo.*;
import org.jitsi.protocol.xmpp.*;
import org.jitsi.protocol.xmpp.util.*;
import org.jivesoftware.smack.*;
import org.jivesoftware.smack.filter.*;
import org.jivesoftware.smack.packet.*;

import java.util.*;

/**
 *
 */
public class MockParticipant
    implements PacketListener,
               PacketFilter
{
    /**
     * The logger.
     */
    private final static Logger logger
        = Logger.getLogger(MockParticipant.class);

    private final static Random random = new Random(System.nanoTime());

    private final String nick;

    private final boolean useBundle;

    private XmppPeer xmppPeer;

    private MockRoomMember user;

    private MockXmppConnection mockConnection;

    private UtilityJingleOpSet jingle;

    private ArrayList<ContentPacketExtension> myContents;

    private final Object addSourceLock = new Object();

    private final Object joinLock = new Object();

    private MediaSSRCMap remoteSSRCs = new MediaSSRCMap();

    private MediaSSRCGroupMap remoteSSRCgroups = new MediaSSRCGroupMap();

    private HashMap<String, IceUdpTransportPacketExtension> transportMap;

    private JingleSession jingleSession;

    private JingleHandler jingleHandler = new JingleHandler();

    private String myJid;

    private String remoteJid;

    private MediaSSRCMap localSSRCs = new MediaSSRCMap();

    private MediaSSRCGroupMap localSSRCGroups = new MediaSSRCGroupMap();

    private String ssrcVideoType = SSRCInfoPacketExtension.CAMERA_VIDEO_TYPE;

    private boolean useSsrcGroups;

    public MockParticipant(String nick)
    {
        this(nick, true);
    }

    public MockParticipant(String nick, boolean useBundle)
    {
        this.nick = nick;
        this.useBundle = useBundle;
    }

    public MockRoomMember getChatMember()
    {
        return user;
    }

    public void joinInNewThread(final MockMultiUserChat chat)
    {
        new Thread(new Runnable()
        {
            @Override
            public void run()
            {
                join(chat);
            }
        }).start();
    }

    public void waitForJoinThread(long timeout)
            throws InterruptedException
    {
        synchronized (joinLock)
        {
            if (xmppPeer == null)
            {
                joinLock.wait(timeout);
            }
            if (xmppPeer == null)
            {
                throw new RuntimeException(
                    "Failed to join the room within" +
                            " the time limit specified: " + timeout);
            }
        }
    }

    public void join(MockMultiUserChat chat)
    {
        user = chat.createMockRoomMember(nick);

        user.setupFeatures(useBundle);

        initContents();

        MockProtocolProvider protocolProvider
            = (MockProtocolProvider)chat.getParentProvider();

        mockConnection = protocolProvider.getMockXmppConnection();

        OperationSetDirectSmackXmpp smackOpSet
            = protocolProvider.getOperationSet(
                    OperationSetDirectSmackXmpp.class);

        myJid = chat.getName() + "/" + user.getName();

        this.jingle = new UtilityJingleOpSet(myJid, mockConnection, smackOpSet);

        jingle.init();

        mockConnection.addPacketHandler(this, this);

        chat.mockJoin(user);

        synchronized (joinLock)
        {
            xmppPeer = new XmppPeer(
                user.getContactAddress(), mockConnection);

            xmppPeer.start();

            joinLock.notifyAll();
        }
    }

    public static long nextSSRC()
    {
        return random.nextInt(Integer.MAX_VALUE);
    }

    private void initContents()
    {
        myContents = new ArrayList<ContentPacketExtension>();

        // AUDIO
        ContentPacketExtension audio = new ContentPacketExtension();
        audio.setName("audio");

        RtpDescriptionPacketExtension audioRtpDesc
            = new RtpDescriptionPacketExtension();
        audioRtpDesc.setMedia("audio");
        audio.addChildExtension(audioRtpDesc);

        SourcePacketExtension audioSSRC
            = new SourcePacketExtension();
        audioSSRC.setSSRC(nextSSRC());
        SSRCSignaling.setSSRCOwner(audioSSRC, myJid);
        audioRtpDesc.addChildExtension(audioSSRC);

        myContents.add(audio);
        localSSRCs.getSSRCsForMedia("audio").add(audioSSRC);

        // VIDEO
        ContentPacketExtension video = new ContentPacketExtension();
        video.setName("video");

        RtpDescriptionPacketExtension videoRtpDesc
            = new RtpDescriptionPacketExtension();
        videoRtpDesc.setMedia("video");
        video.addChildExtension(videoRtpDesc);

        SourcePacketExtension videoSSRC = new SourcePacketExtension();

        videoSSRC.setSSRC(nextSSRC());

        SSRCSignaling.setSSRCOwner(videoSSRC, myJid);

        SSRCSignaling.setSSRCVideoType(videoSSRC, ssrcVideoType);

        videoRtpDesc.addChildExtension(videoSSRC);

        localSSRCs.getSSRCsForMedia("video").add(videoSSRC);

        if (useSsrcGroups)
        {
            // 2nd video SSRC
            SourcePacketExtension videoSSRC2
                = new SourcePacketExtension();
            videoSSRC2.setSSRC(nextSSRC());

            videoRtpDesc.addChildExtension(videoSSRC2);
            localSSRCs.getSSRCsForMedia("video").add(videoSSRC2);

            // Audio SSRC group
            SourceGroupPacketExtension ssrcGroupPe
                = SourceGroupPacketExtension.createSimulcastGroup();

            List<SourcePacketExtension> ssrcs
                = new ArrayList<SourcePacketExtension>();
            ssrcs.add(videoSSRC);
            ssrcs.add(videoSSRC2);
            ssrcGroupPe.addSources(ssrcs);
            videoRtpDesc.addChildExtension(ssrcGroupPe);
            localSSRCGroups.getSSRCGroupsForMedia("video")
                .add(new SSRCGroup(ssrcGroupPe));
        }

        myContents.add(video);
    }

    public void acceptInviteInBg()
    {
        new Thread(new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    acceptInvite(5000);
                }
                catch (InterruptedException e)
                {
                    logger.error(e, e);
                }
            }
        },"Accept invite " + nick).start();
    }

    public JingleIQ[] acceptInvite(long timeout)
        throws InterruptedException
    {
        JingleIQ invite = jingle.acceptSession(timeout, jingleHandler);
        if (invite == null)
        {
            throw new RuntimeException(nick + " - wait for invite timeout");
        }

        logger.info(nick + " invite: " + invite.toXML());

        // ACK invite
        IQ inviteAck = JingleIQ.createResultIQ(invite);
        mockConnection.sendPacket(inviteAck);

        JingleIQ user1Accept = generateSessionAccept(
            invite,
            createTransportMap(invite));

        logger.info(nick + " accept: " + user1Accept.toXML());

        mockConnection.sendPacket(user1Accept);

        this.myJid = user1Accept.getFrom();
        this.remoteJid = user1Accept.getTo();
        this.jingleSession = jingle.getSession(invite.getSID());

        return new JingleIQ[] { invite, user1Accept };
    }

    public void leave()
    {
        xmppPeer.stop();

        user.leave();
    }

    private Map<String, IceUdpTransportPacketExtension> createTransportMap(
            JingleIQ user1Invite)
    {
        this.transportMap
            = new HashMap<String, IceUdpTransportPacketExtension>();

        for (ContentPacketExtension content : user1Invite.getContentList())
        {
            IceUdpTransportPacketExtension transport
                = new IceUdpTransportPacketExtension();

            if (useBundle)
            {
                // Bundle uses RTCP mux
                transport.addChildExtension(new RtcpmuxPacketExtension());
            }

            DtlsFingerprintPacketExtension dtlsFingerprint
                = new DtlsFingerprintPacketExtension();

            IceUdpTransportPacketExtension offerTransport
                = content.getFirstChildOfType(
                        IceUdpTransportPacketExtension.class);

            DtlsFingerprintPacketExtension offerFingerprint
                = offerTransport.getFirstChildOfType(
                        DtlsFingerprintPacketExtension.class);

            dtlsFingerprint.setHash(offerFingerprint.getHash());
            dtlsFingerprint.setFingerprint(
                offerFingerprint.getFingerprint()
                    .replace("A", "B")
                    .replace("1", "2")
                    .replace("C", "D")
            );

            transport.addChildExtension(dtlsFingerprint);

            transportMap.put(content.getName(), transport);
        }

        return transportMap;
    }

    public Map<String, IceUdpTransportPacketExtension> generateFakeCandidates()
    {
        for (ContentPacketExtension content : myContents)
        {
            IceUdpTransportPacketExtension iceTransport
                = transportMap.get(content.getName());

            CandidatePacketExtension fakeCandidate
                = new CandidatePacketExtension();

            fakeCandidate.setIP("127.0.0.1");
            fakeCandidate.setPort(60000);

            iceTransport.addCandidate(fakeCandidate);
        }
        return transportMap;
    }

    public JingleIQ sendTransportInfo()
    {
        List<ContentPacketExtension> contents
            = new ArrayList<ContentPacketExtension>();

        for (ContentPacketExtension myContent : myContents)
        {
            ContentPacketExtension content = new ContentPacketExtension();
            content.setName(myContent.getName());

            try
            {
                IceUdpTransportPacketExtension transportCopy
                    = IceUdpTransportPacketExtension
                            .cloneTransportAndCandidates(
                                transportMap.get(content.getName()), true);

                content.addChildExtension(transportCopy);

                contents.add(content);
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }
        }

        JingleIQ transportInfoIq
            = JinglePacketFactory.createTransportInfo(
                myJid, remoteJid, jingleSession.getSessionID(), contents);

        mockConnection.sendPacket(transportInfoIq);

        return transportInfoIq;
    }

    private JingleIQ generateSessionAccept(
            JingleIQ sessionInit,
            Map<String, IceUdpTransportPacketExtension> transportMap)
    {
        JingleIQ accept = new JingleIQ();

        accept.setPacketID(Packet.nextID());
        accept.setType(IQ.Type.SET);

        accept.setAction(JingleAction.SESSION_ACCEPT);

        accept.setSID(sessionInit.getSID());
        accept.setFrom(sessionInit.getTo());
        accept.setTo(sessionInit.getFrom());

        // Jingle BUNDLE extension
        accept.addExtension(
            GroupPacketExtension.createBundleGroup(
                sessionInit.getContentList()));

        for (ContentPacketExtension contentOffer : myContents)
        {
            ContentPacketExtension acceptContent
                = new ContentPacketExtension();

            acceptContent.setName(contentOffer.getName());

            acceptContent.setCreator(
                ContentPacketExtension.CreatorEnum.responder);

            acceptContent.setSenders(contentOffer.getSenders());

            acceptContent.addChildExtension(
                transportMap.get(contentOffer.getName()));

            // Copy RTPDescription
            acceptContent.addChildExtension(
                contentOffer.getFirstChildOfType(
                    RtpDescriptionPacketExtension.class));

            accept.addContent(acceptContent);
        }

        return accept;
    }

    public String getNickname()
    {
        return nick;
    }

    @Override
    public boolean accept(Packet packet)
    {
        boolean isJingle
            = user.getContactAddress().equals(packet.getTo())
                    && packet instanceof JingleIQ;

        if (!isJingle)
            return false;

        JingleIQ jingleIQ = (JingleIQ) packet;
        return JingleAction.SOURCEADD.equals(jingleIQ.getAction())
            || JingleAction.SOURCEREMOVE.equals(jingleIQ.getAction());
    }

    @Override
    public void processPacket(Packet packet)
    {
        JingleIQ modifySSRcIq = (JingleIQ) packet;
        JingleAction action = modifySSRcIq.getAction();

        if (JingleAction.SOURCEADD.equals(action))
        {
            MediaSSRCMap ssrcMap
                = MediaSSRCMap.getSSRCsFromContent(
                        modifySSRcIq.getContentList());

            remoteSSRCs.add(ssrcMap);

            MediaSSRCGroupMap ssrcGroupMap
                = MediaSSRCGroupMap.getSSRCGroupsForContents(
                        modifySSRcIq.getContentList());

            remoteSSRCgroups.add(ssrcGroupMap);

            synchronized (addSourceLock)
            {
                logger.info("source-add received " + nick);

                addSourceLock.notifyAll();
            }
        }
        else if (JingleAction.SOURCEREMOVE.equals(action))
        {
            MediaSSRCMap ssrcsToRemove
                = MediaSSRCMap.getSSRCsFromContent(
                    modifySSRcIq.getContentList());

            remoteSSRCs.remove(ssrcsToRemove);

            MediaSSRCGroupMap ssrcGroupsToRemove
                = MediaSSRCGroupMap.getSSRCGroupsForContents(
                    modifySSRcIq.getContentList());

            remoteSSRCgroups.remove(ssrcGroupsToRemove);

            logger.info("source-remove received " + nick);
        }
    }

    public void switchVideoSSRCs(long[] newVideoSSRCs, boolean useSsrcGroups)
    {
        MediaSSRCMap toRemove = new MediaSSRCMap();
        toRemove.getSSRCsForMedia("video").addAll(
            localSSRCs.getSSRCsForMedia("video"));

        // Send source-remove
        jingle.sendRemoveSourceIQ(
            toRemove, localSSRCGroups.copy(), jingleSession);

        // Remove old ssrcs(and clear groups)
        localSSRCs.remove(toRemove);
        localSSRCGroups = new MediaSSRCGroupMap();

        MediaSSRCMap toAdd = new MediaSSRCMap();
        // Create new SSRCs
        for (long ssrc : newVideoSSRCs)
        {
            SourcePacketExtension ssrcPe = new SourcePacketExtension();
            ssrcPe.setSSRC(ssrc);
            localSSRCs.getSSRCsForMedia("video").add(ssrcPe);
            toAdd.getSSRCsForMedia("video").add(ssrcPe);
        }
        // Create SSRC group
        if (useSsrcGroups)
        {
            SourceGroupPacketExtension ssrcGroup
                = SourceGroupPacketExtension.createSimulcastGroup();
            ssrcGroup.addSources(localSSRCs.getSSRCsForMedia("video"));
            localSSRCGroups.addSSRCGroup("video", new SSRCGroup(ssrcGroup));
        }
        // Send source-add
        jingle.sendAddSourceIQ(toAdd, localSSRCGroups, jingleSession);
    }

    public List<SourcePacketExtension> getRemoteSSRCs(String media)
    {
        return remoteSSRCs.getSSRCsForMedia(media);
    }

    public List<SSRCGroup> getRemoteSSRCGroups(String media)
    {
        return remoteSSRCgroups.getSSRCGroupsForMedia(media);
    }

    public void waitForAddSource(long timeout)
    {
        synchronized (addSourceLock)
        {
            try
            {
                addSourceLock.wait(timeout);
            }
            catch (InterruptedException e)
            {
                throw new RuntimeException(e);
            }
        }
    }

    public boolean isUseSsrcGroups()
    {
        return useSsrcGroups;
    }

    public void setUseSsrcGroups(boolean useSsrcGroups)
    {
        this.useSsrcGroups = useSsrcGroups;
    }

    public String getMyJid()
    {
        return myJid;
    }

    public List<SourcePacketExtension> getVideoSSRCS()
    {
        return localSSRCs.getSSRCsForMedia("video");
    }

    public String getSsrcVideoType()
    {
        return ssrcVideoType;
    }

    public void setSsrcVideoType(String ssrcVideoType)
    {
        this.ssrcVideoType = ssrcVideoType;
    }

    class JingleHandler extends DefaultJingleRequestHandler
    {

    }
}
