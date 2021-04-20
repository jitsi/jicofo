/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2015-Present 8x8, Inc.
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

import org.jetbrains.annotations.*;
import org.jitsi.impl.protocol.xmpp.*;
import org.jitsi.jicofo.xmpp.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.xmpp.extensions.colibri.*;
import org.jitsi.xmpp.extensions.jingle.*;
import org.jitsi.xmpp.extensions.jitsimeet.*;

import org.jitsi.protocol.xmpp.*;
import org.jitsi.protocol.xmpp.util.*;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.packet.id.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;
import org.jxmpp.stringprep.*;

import java.util.*;
import java.util.concurrent.*;

import static org.jitsi.impl.protocol.xmpp.ChatRoomMemberPresenceChangeEvent.*;

public class MockParticipant implements ChatRoomMemberPresenceListener
{
    private final static Logger logger = new LoggerImpl(MockParticipant.class.getName());

    private final static Random random = new Random(System.nanoTime());

    private final String nick;

    private XmppPeer xmppPeer;

    private MockRoomMember user;

    private MockXmppConnection mockConnection;

    private UtilityJingleOpSet jingle;

    private ArrayList<ContentPacketExtension> myContents;

    private final Object sourceLock = new Object();

    private final Object joinLock = new Object();

    private final MediaSourceMap remoteSSRCs = new MediaSourceMap();

    private final MediaSourceGroupMap remoteSSRCgroups = new MediaSourceGroupMap();

    private HashMap<String, IceUdpTransportPacketExtension> transportMap;

    private JingleSession jingleSession;

    private final JingleHandler jingleHandler = new JingleHandler();

    private Jid myJid;

    private Jid remoteJid;

    private final MediaSourceMap localSSRCs = new MediaSourceMap();

    private final MediaSourceGroupMap localSSRCGroups = new MediaSourceGroupMap();

    private String ssrcVideoType = SSRCInfoPacketExtension.CAMERA_VIDEO_TYPE;

    private final BlockingQueue<JingleIQ> ssrcAddQueue = new LinkedBlockingQueue<>();

    private final BlockingQueue<JingleIQ> ssrcRemoveQueue = new LinkedBlockingQueue<>();

    private final CountDownLatch sessionInitiateLatch = new CountDownLatch(1);

    public MockParticipant(String nick)
    {
        this.nick = nick;
    }

    public MockRoomMember getChatMember()
    {
        return user;
    }

    public void joinInNewThread(final MockChatRoom chat)
    {
        new Thread(() -> join(chat)).start();
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

    public void join(MockChatRoom chat)
    {
        try
        {
            user = chat.createMockRoomMember(nick);
        }
        catch (XmppStringprepException e)
        {
            throw new RuntimeException(e);
        }

        try
        {
            myJid = JidCreate.entityFullFrom(chat.getName() + "/" + user.getName());
        }
        catch (XmppStringprepException e)
        {
            throw new RuntimeException(e);
        }

        mockConnection = new MockXmppConnection(myJid);
        jingle = new UtilityJingleOpSet(mockConnection);
        jingle.mockParticipant = this;
        mockConnection.registerIQRequestHandler(jingle);

        chat.mockJoin(user);
        synchronized (joinLock)
        {
            xmppPeer = new XmppPeer(user.getJid(), mockConnection);
            xmppPeer.start();
            joinLock.notifyAll();
        }
        chat.addMemberPresenceListener(this);
    }

    public static long nextSSRC()
    {
        return random.nextInt(Integer.MAX_VALUE);
    }

    private SourcePacketExtension newSSRC(long ssrc, String ssrcVideoType)
    {
        SourcePacketExtension ssrcPe = new SourcePacketExtension();

        ssrcPe.setSSRC(ssrc);

        SSRCSignaling.setSSRCOwner(ssrcPe, myJid);

        if (ssrcVideoType != null)
            SSRCSignaling.setSSRCVideoType(ssrcPe, ssrcVideoType);

        return ssrcPe;
    }

    private void initContents()
    {
        myContents = new ArrayList<>();

        // AUDIO
        ContentPacketExtension audio = new ContentPacketExtension();
        audio.setName("audio");

        RtpDescriptionPacketExtension audioRtpDesc
            = new RtpDescriptionPacketExtension();
        audioRtpDesc.setMedia("audio");
        audio.addChildExtension(audioRtpDesc);

        addLocalAudioSSRC(nextSSRC());

        for (SourcePacketExtension ssrc : localSSRCs.getSourcesForMedia("audio"))
        {
            audioRtpDesc.addChildExtension(ssrc);
        }

        myContents.add(audio);

        // VIDEO
        ContentPacketExtension video = new ContentPacketExtension();
        video.setName("video");

        RtpDescriptionPacketExtension videoRtpDesc
            = new RtpDescriptionPacketExtension();
        videoRtpDesc.setMedia("video");
        video.addChildExtension(videoRtpDesc);

        // Add video SSRC
        addLocalVideoSSRC(nextSSRC(), ssrcVideoType);

        for (SourcePacketExtension videoSSRC : getVideoSSRCS())
        {
            videoRtpDesc.addChildExtension(videoSSRC);
        }

        myContents.add(video);
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
        UtilKt.tryToSendStanza(mockConnection, inviteAck);

        initContents();

        processStanza(invite);

        JingleIQ user1Accept = generateSessionAccept(invite, createTransportMap(invite));

        logger.info(nick + " accept: " + user1Accept.toXML());

        UtilKt.tryToSendStanza(mockConnection, user1Accept);

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
        this.transportMap = new HashMap<>();

        for (ContentPacketExtension content : user1Invite.getContentList())
        {
            IceUdpTransportPacketExtension transport
                = new IceUdpTransportPacketExtension();

            // Bundle uses RTCP mux
            transport.addChildExtension(new RtcpmuxPacketExtension());

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

    private JingleIQ generateSessionAccept(
            JingleIQ sessionInit,
            Map<String, IceUdpTransportPacketExtension> transportMap)
    {
        JingleIQ accept = new JingleIQ(JingleAction.SESSION_ACCEPT, sessionInit.getSID());

        accept.setStanzaId(StanzaIdUtil.newStanzaId());
        accept.setType(IQ.Type.set);
        accept.setFrom(sessionInit.getTo());
        accept.setTo(sessionInit.getFrom());

        for (ContentPacketExtension contentOffer : myContents)
        {
            ContentPacketExtension acceptContent = new ContentPacketExtension();

            acceptContent.setName(contentOffer.getName());

            acceptContent.setCreator(ContentPacketExtension.CreatorEnum.responder);

            acceptContent.setSenders(contentOffer.getSenders());

            acceptContent.addChildExtension(transportMap.get(contentOffer.getName()));

            // Copy RTPDescription
            acceptContent.addChildExtension(contentOffer.getFirstChildOfType(RtpDescriptionPacketExtension.class));

            accept.addContent(acceptContent);
        }

        return accept;
    }

    public String getNickname()
    {
        return nick;
    }

    public void processStanza(IQ packet)
    {
        JingleIQ modifySSRcIq = (JingleIQ) packet;
        JingleAction action = modifySSRcIq.getAction();

        if (JingleAction.SESSION_INITIATE.equals(action))
        {
            synchronized (sourceLock)
            {
                MediaSourceMap ssrcMap
                        = MediaSourceMap.getSourcesFromContent(modifySSRcIq.getContentList());

                remoteSSRCs.add(ssrcMap);

                MediaSourceGroupMap ssrcGroupMap
                        = MediaSourceGroupMap.getSourceGroupsForContents(modifySSRcIq.getContentList());

                remoteSSRCgroups.add(ssrcGroupMap);

                logger.info(nick + " received session-initiate: " + ssrcMap  + " groups: " + ssrcGroupMap);

                sourceLock.notifyAll();

                sessionInitiateLatch.countDown();
            }
        }
        else  if (JingleAction.SOURCEADD.equals(action)
                    || JingleAction.ADDSOURCE.equals(action))
        {
            synchronized (sourceLock)
            {
                MediaSourceMap ssrcMap = MediaSourceMap.getSourcesFromContent(modifySSRcIq.getContentList());

                remoteSSRCs.add(ssrcMap);

                MediaSourceGroupMap ssrcGroupMap
                    = MediaSourceGroupMap.getSourceGroupsForContents(modifySSRcIq.getContentList());

                remoteSSRCgroups.add(ssrcGroupMap);

                logger.info(nick + " received source-add " + ssrcMap);

                try
                {
                    ssrcAddQueue.put(modifySSRcIq);
                }
                catch (InterruptedException e)
                {
                    throw new RuntimeException(e);
                }

                sourceLock.notifyAll();
            }
        }
        else if (JingleAction.SOURCEREMOVE.equals(action)
                || JingleAction.REMOVESOURCE.equals(action))
        {
            synchronized (sourceLock)
            {
                MediaSourceMap ssrcsToRemove = MediaSourceMap.getSourcesFromContent(modifySSRcIq.getContentList());

                remoteSSRCs.remove(ssrcsToRemove);

                MediaSourceGroupMap ssrcGroupsToRemove
                    = MediaSourceGroupMap.getSourceGroupsForContents(modifySSRcIq.getContentList());

                remoteSSRCgroups.remove(ssrcGroupsToRemove);

                logger.info(nick + " source-remove received " + ssrcsToRemove);

                try
                {
                    ssrcRemoveQueue.put(modifySSRcIq);
                }
                catch (InterruptedException e)
                {
                    throw new RuntimeException(e);
                }

                sourceLock.notifyAll();
            }
        }
    }

    private SourceGroup getLocalSSRCGroup(String media)
    {
        List<SourceGroup> videoGroups
            = localSSRCGroups.getSourceGroupsForMedia(media);
        SourceGroup group = null;
        if (videoGroups.size() > 0)
        {
            group = videoGroups.get(0);
        }
        if (videoGroups.size() == 0)
        {
            SourceGroupPacketExtension ssrcGroup = SourceGroupPacketExtension.createSimulcastGroup();

            group = new SourceGroup(ssrcGroup);

            localSSRCGroups.addSourceGroup(media, group);
        }
        return group;
    }

    public List<SourcePacketExtension> videoSourceAdd(int count)
    {
        return sourceAdd("video", count, false, null);
    }

    public List<SourcePacketExtension> audioSourceAdd(int count)
    {
        return sourceAdd("audio", count, false, null);
    }

    public void audioSourceRemove(int count)
    {
        List<SourcePacketExtension> audioSources = this.localSSRCs.getSourcesForMedia("audio");

        if (audioSources.size() < count)
        {
            throw new IllegalArgumentException("audio source size(" + audioSources.size() + ") < count(" + count + ")");
        }

        List<SourcePacketExtension> toRemove = new ArrayList<>(count);

        for(int i = 0; i < count; i++)
        {
            toRemove.add(audioSources.remove(0));
        }

        MediaSourceMap removeMap = new MediaSourceMap();

        removeMap.addSources("audio", toRemove);

        jingle.sendRemoveSourceIQ(removeMap, null, jingleSession);
    }

    private List<SourcePacketExtension> sourceAdd(String media, int count, boolean useGroups, String[] videoTypes)
    {
        long[] ssrcs = new long[count];
        for (int i=0; i<count; i++)
        {
            ssrcs[i] = nextSSRC();
        }
        return sourceAdd(media, ssrcs, useGroups, videoTypes);
    }

    public List<SourcePacketExtension> videoSourceAdd(long[] newSSRCs, boolean useSsrcGroups)
    {
        return sourceAdd("video", newSSRCs, useSsrcGroups, null);
    }

    public List<SourcePacketExtension> sourceAdd(
        String media, long[] newSSRCs, boolean useSsrcGroups, String[] ssrcsVideoTypes)
    {
        List<SourcePacketExtension> addedSSRCs = new ArrayList<>(newSSRCs.length);
        MediaSourceMap toAdd = new MediaSourceMap();
        SourceGroup sourceGroup = getLocalSSRCGroup(media);

        // Create new SSRCs
        for (int i=0; i<newSSRCs.length; i++)
        {
            String videoType = null;
            if (ssrcsVideoTypes != null)
            {
                videoType = ssrcsVideoTypes[i];
            }

            SourcePacketExtension ssrcPe = addLocalSSRC(media, newSSRCs[i], videoType);

            toAdd.addSource(media, ssrcPe);
            addedSSRCs.add(ssrcPe);

            if (useSsrcGroups)
            {
                sourceGroup.addSource(ssrcPe);
            }
        }

        // Send source-add
        jingle.sendAddSourceIQ(toAdd, localSSRCGroups, jingleSession);

        return addedSSRCs;
    }

    public List<SourcePacketExtension> getRemoteSSRCs(String media)
    {
        return remoteSSRCs.getSourcesForMedia(media);
    }

    public List<SourceGroup> getRemoteSSRCGroups(String media)
    {
        return remoteSSRCgroups.getSourceGroupsForMedia(media);
    }

    public JingleIQ waitForAddSource(long timeout)
        throws InterruptedException
    {
        return ssrcAddQueue.poll(timeout, TimeUnit.MILLISECONDS);
    }

    public void waitForSessionInitiate()
    {
        waitForSessionInitiate(3000);
    }

    public void waitForSessionInitiate(long timeoutMs)
    {
        try
        {
            sessionInitiateLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException e)
        {
            throw new RuntimeException(e);
        }
    }

    public JingleIQ waitForRemoveSource(long timeout)
        throws InterruptedException
    {
        return ssrcRemoveQueue.poll(timeout, TimeUnit.MILLISECONDS);
    }

    public Jid getMyJid()
    {
        return myJid;
    }

    public List<SourcePacketExtension> getVideoSSRCS()
    {
        return localSSRCs.getSourcesForMedia("video");
    }

    public String getSsrcVideoType()
    {
        return ssrcVideoType;
    }

    public void setSsrcVideoType(String ssrcVideoType)
    {
        this.ssrcVideoType = ssrcVideoType;
    }

    public List<SourcePacketExtension> addMultipleAudioSSRCs(int count)
    {
        List<SourcePacketExtension> newSSRCs = new ArrayList<>(count);
        for (int i=0; i<count; i++)
        {
            newSSRCs.add(addLocalAudioSSRC(nextSSRC()));
        }
        return newSSRCs;
    }

    public List<SourcePacketExtension> addMultipleVideoSSRCs(int count)
    {
        List<SourcePacketExtension> newSSRCs = new ArrayList<>(count);
        for (int i=0; i<count; i++)
        {
            newSSRCs.add(addLocalVideoSSRC(nextSSRC(), null));
        }
        return newSSRCs;
    }

    public SourcePacketExtension addLocalSSRC(
        String media, long ssrc, String videoType)
    {
        SourcePacketExtension newSSRC = newSSRC(ssrc, videoType);

        localSSRCs.addSource(media, newSSRC);

        return newSSRC;
    }

    public SourcePacketExtension addLocalVideoSSRC(long ssrc, String videoType)
    {
        return addLocalSSRC("video", ssrc, videoType);
    }

    public SourcePacketExtension addLocalAudioSSRC(long ssrc)
    {
        return addLocalSSRC("audio", ssrc, null);
    }

    @Override
    public void memberPresenceChanged(@NotNull ChatRoomMemberPresenceChangeEvent evt)
    {
        if (evt instanceof Left || evt instanceof Kicked)
        {
            ChatRoomMember memberWhoLeft = evt.getChatRoomMember();
            removeSsrcs(memberWhoLeft);
        }
    }

    private void removeSsrcs(ChatRoomMember member)
    {
        SourcePacketExtension audioSsrc = remoteSSRCs.findSsrcForOwner("audio", member.getJid());
        if (audioSsrc != null)
        {
            remoteSSRCs.remove("audio", audioSsrc);
        }

        SourcePacketExtension videoSsrc = remoteSSRCs.findSsrcForOwner("video", member.getJid());
        if (videoSsrc != null)
        {
            remoteSSRCs.remove("video", videoSsrc);
        }

        // We should also remove the member's SSRC groups, but we have no easy way of finding which ones they are.
    }

    static class JingleHandler
        implements JingleRequestHandler
    {
        /**
         * The logger used by this instance.
         */
        private final static Logger logger = new LoggerImpl(JingleHandler.class.getName());

        @Override
        public XMPPError onAddSource(JingleSession jingleSession,
            List<ContentPacketExtension> contents)
        {
            logger.warn("Ignored Jingle 'source-add'");

            return null;
        }

        @Override
        public XMPPError onRemoveSource(JingleSession jingleSession,
            List<ContentPacketExtension> contents)
        {
            logger.warn("Ignored Jingle 'source-remove'");

            return null;
        }

        @Override
        public XMPPError onSessionAccept(JingleSession jingleSession,
            List<ContentPacketExtension> answer)
        {
            logger.warn("Ignored Jingle 'session-accept'");

            return null;
        }

        @Override
        public XMPPError onSessionTerminate(JingleSession jingleSession, JingleIQ iq)
        {
            logger.warn("Ignored Jingle 'session-terminate'");

            return null;
        }

        @Override
        public XMPPError onSessionInfo(JingleSession session, JingleIQ iq)
        {
            logger.warn("Ignored Jingle 'session-info'");

            return null;
        }

        @Override
        public XMPPError onTransportAccept(JingleSession jingleSession,
            List<ContentPacketExtension> contents)
        {
            logger.warn("Ignored Jingle 'transport-accept'");

            return null;
        }

        @Override
        public void onTransportInfo(JingleSession jingleSession,
            List<ContentPacketExtension> contents)
        {
            logger.warn("Ignored Jingle 'transport-info'");
        }

        @Override
        public void onTransportReject(JingleSession jingleSession,
            JingleIQ      rejectIQ)
        {
            logger.warn("Ignored Jingle 'transport-reject'");
        }
    }
}
