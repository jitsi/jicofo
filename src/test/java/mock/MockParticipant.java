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

import edu.umd.cs.findbugs.annotations.*;
import mock.muc.*;
import mock.util.*;
import mock.xmpp.*;

import org.jetbrains.annotations.*;
import org.jitsi.impl.protocol.xmpp.*;
import org.jitsi.jicofo.conference.source.*;
import org.jitsi.jicofo.xmpp.*;
import org.jitsi.jicofo.xmpp.muc.*;
import org.jitsi.utils.*;
import org.jitsi.utils.logging2.*;
import org.jitsi.xmpp.extensions.jingle.*;

import org.jitsi.protocol.xmpp.*;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smack.packet.id.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;
import org.jxmpp.stringprep.*;

import java.util.*;
import java.util.concurrent.*;

public class MockParticipant
{
    private final static Logger logger = new LoggerImpl(MockParticipant.class.getName());

    private final static Random random = new Random(System.nanoTime());

    private final static StanzaIdSourceFactory stanzaIdSourceFactory = new StandardStanzaIdSource.Factory();

    private final StanzaIdSource stanzaIdSource = stanzaIdSourceFactory.constructStanzaIdSource();

    private final String nick;

    private XmppPeer xmppPeer;

    private MockRoomMember user;

    private MockXmppConnection mockConnection;

    private UtilityJingleOpSet jingle;

    private ArrayList<ContentPacketExtension> myContents;

    private final Object sourceLock = new Object();

    private final Object joinLock = new Object();

    private final ConferenceSourceMap remoteSSRCs = new ConferenceSourceMap();

    private HashMap<String, IceUdpTransportPacketExtension> transportMap;

    private JingleSession jingleSession;

    private final JingleHandler jingleHandler = new JingleHandler();

    private Jid myJid;

    private final ConferenceSourceMap localSSRCs = new ConferenceSourceMap();

    private final BlockingQueue<JingleIQ> ssrcAddQueue = new LinkedBlockingQueue<>();

    private final BlockingQueue<JingleIQ> ssrcRemoveQueue = new LinkedBlockingQueue<>();

    private final CountDownLatch sessionInitiateLatch = new CountDownLatch(1);

    private final ChatRoomListener chatRoomListener = new ChatRoomListenerImpl();

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
            myJid = JidCreate.entityFullFrom(chat.getRoomJid() + "/" + user.getName());
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
        chat.addListener(chatRoomListener);
    }

    public static long nextSSRC()
    {
        return random.nextInt(Integer.MAX_VALUE);
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

        // VIDEO
        ContentPacketExtension video = new ContentPacketExtension();
        video.setName("video");

        RtpDescriptionPacketExtension videoRtpDesc
                = new RtpDescriptionPacketExtension();
        videoRtpDesc.setMedia("video");
        video.addChildExtension(videoRtpDesc);

        // Add video SSRC
        addLocalVideoSSRC(nextSSRC());

        localSSRCs.values().forEach(endpointSourceSet -> {
            endpointSourceSet.getSources().forEach(source -> {
                if (source.getMediaType() == MediaType.AUDIO)
                {
                    audioRtpDesc.addChildExtension(source.toPacketExtension(getMyJid()));
                }
                else
                {
                    videoRtpDesc.addChildExtension(source.toPacketExtension(getMyJid()));
                }
            });
        });

        myContents.add(audio);
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

        accept.setStanzaId(stanzaIdSource.getNewStanzaId());
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
                ConferenceSourceMap sources = ExtensionsKt.parseConferenceSourceMap(modifySSRcIq.getContentList());
                remoteSSRCs.add(sources);
                logger.info(nick + " received session-initiate with sources: " + sources);

                sourceLock.notifyAll();

                sessionInitiateLatch.countDown();
            }
        }
        else  if (JingleAction.SOURCEADD.equals(action)
                    || JingleAction.ADDSOURCE.equals(action))
        {
            synchronized (sourceLock)
            {
                ConferenceSourceMap sources = ExtensionsKt.parseConferenceSourceMap(modifySSRcIq.getContentList());
                remoteSSRCs.add(sources);

                logger.info(nick + " received source-add with sources:" + sources);

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
                ConferenceSourceMap sourcesToRemove
                        = ExtensionsKt.parseConferenceSourceMap(modifySSRcIq.getContentList());
                remoteSSRCs.remove(sourcesToRemove);

                logger.info(nick + " source-remove received with sources:" + sourcesToRemove);

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

    @SuppressFBWarnings("NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE")
    public void audioSourceRemove()
    {
        // Remove 1 audio source.
        Source audioSource = localSSRCs.get(getMyJid()).getSources().stream().findFirst().orElse(null);

        if (audioSource == null)
        {
            throw new IllegalArgumentException("no audio source available to remove");
        }

        ConferenceSourceMap toRemove = new ConferenceSourceMap(getMyJid(), new EndpointSourceSet(audioSource));

        localSSRCs.remove(toRemove);
        jingle.sendRemoveSourceIQ(toRemove, jingleSession, false);
    }

    public void videoSourceAdd(long[] newSSRCs)
    {
        ConferenceSourceMap toAdd = new ConferenceSourceMap();

        // Create new SSRCs
        for (int i=0; i<newSSRCs.length; i++)
        {
            Source ssrcPe = addLocalSSRC("video", newSSRCs[i]);

            toAdd.add(getMyJid(), new EndpointSourceSet(ssrcPe));
        }

        // Send source-add
        jingle.sendAddSourceIQ(toAdd, jingleSession, false);
    }

    public ConferenceSourceMap getRemoteSources()
    {
        return remoteSSRCs;
    }

    public int numRemoteSourcesOfType(MediaType mediaType)
    {
        return ExtensionsKt.numSourcesOfType(remoteSSRCs, mediaType);
    }

    public int numRemoteSourceGroupsOfType(MediaType mediaType)
    {
        return ExtensionsKt.numSourceGroupsOfype(remoteSSRCs, mediaType);
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

    public Source addLocalSSRC(String media, long ssrc)
    {
        Source source = new Source(ssrc, MediaType.parseString(media), null, null, false);

        localSSRCs.add(getMyJid(), new EndpointSourceSet(source));

        return source;
    }

    public void addLocalVideoSSRC(long ssrc)
    {
        addLocalSSRC("video", ssrc);
    }

    public void addLocalAudioSSRC(long ssrc)
    {
        addLocalSSRC("audio", ssrc);
    }

    private void removeSsrcs(ChatRoomMember member)
    {
        remoteSSRCs.remove(member.getJid());
    }

    static class JingleHandler
        implements JingleRequestHandler
    {
        /**
         * The logger used by this instance.
         */
        private final static Logger logger = new LoggerImpl(JingleHandler.class.getName());

        @Override
        public StanzaError onAddSource(JingleSession jingleSession,
            List<ContentPacketExtension> contents)
        {
            logger.warn("Ignored Jingle 'source-add'");

            return null;
        }

        @Override
        public StanzaError onRemoveSource(JingleSession jingleSession,
            List<ContentPacketExtension> contents)
        {
            logger.warn("Ignored Jingle 'source-remove'");

            return null;
        }

        @Override
        public StanzaError onSessionAccept(JingleSession jingleSession,
            List<ContentPacketExtension> answer)
        {
            logger.warn("Ignored Jingle 'session-accept'");

            return null;
        }

        @Override
        public StanzaError onSessionTerminate(JingleSession jingleSession, JingleIQ iq)
        {
            logger.warn("Ignored Jingle 'session-terminate'");

            return null;
        }

        @Override
        public StanzaError onSessionInfo(JingleSession session, JingleIQ iq)
        {
            logger.warn("Ignored Jingle 'session-info'");

            return null;
        }

        @Override
        public StanzaError onTransportAccept(JingleSession jingleSession,
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

    private class ChatRoomListenerImpl extends DefaultChatRoomListener
    {
        @Override
        public void memberLeft(@NotNull ChatRoomMember member)
        {
            removeSsrcs(member);
        }

        @Override
        public void memberKicked(@NotNull ChatRoomMember member)
        {
            removeSsrcs(member);
        }
    }
}
