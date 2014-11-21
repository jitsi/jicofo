/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package mock;

import mock.muc.*;
import mock.xmpp.*;

import net.java.sip.communicator.impl.protocol.jabber.extensions.colibri.*;
import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import net.java.sip.communicator.util.*;

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

    private final String nick;

    private final boolean useBundle;

    private XmppPeer xmppPeer;

    private MockRoomMember user;

    private MockXmppConnection mockConnection;

    private ArrayList<ContentPacketExtension> myContents;

    private final Object addSourceLock = new Object();

    private MediaSSRCMap remoteSSRCs = new MediaSSRCMap();

    private HashMap<String, IceUdpTransportPacketExtension> transportMap;

    private String sid;

    private String myJid;

    private String remoteJid;

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

    public void join(MockMultiUserChat chat)
    {
        if (useBundle)
        {
            user = chat.createMockRoomMember(nick);
            user.addBundleSupport();
            chat.mockJoin(user);
        }
        else
        {
            user = chat.mockJoin(nick);
        }

        initContents();

        mockConnection
            = ((MockProtocolProvider)chat.getParentProvider())
                    .getMockXmppConnection();

        mockConnection.addPacketHandler(this, this);

        xmppPeer = new XmppPeer(
            user.getContactAddress(), mockConnection);

        xmppPeer.start();
    }

    private void initContents()
    {
        myContents = new ArrayList<ContentPacketExtension>();

        ContentPacketExtension audio = new ContentPacketExtension();
        audio.setName("audio");

        RtpDescriptionPacketExtension audioRtpDesc
            = new RtpDescriptionPacketExtension();
        audio.addChildExtension(audioRtpDesc);

        audioRtpDesc.setMedia("audio");

        SourcePacketExtension audioSSRC
            = new SourcePacketExtension();

        audioSSRC.setSSRC(System.nanoTime());

        audioRtpDesc.addChildExtension(audioSSRC);

        myContents.add(audio);
    }

    public JingleIQ[] acceptInvite(long timeout)
    {
        JingleIQ user1Invite = (JingleIQ) xmppPeer.waitForPacket(timeout);
        if (user1Invite == null)
        {
            throw new RuntimeException(nick + " - wait for invite timeout");
        }

        logger.info(nick + " invite: " + user1Invite.toXML());

        JingleIQ user1Accept = generateSessionAccept(
            user1Invite,
            createTransportMap(user1Invite));

        logger.info(nick + " accept: " + user1Accept.toXML());

        mockConnection.sendPacket(user1Accept);

        this.sid = user1Accept.getSID();
        this.myJid = user1Accept.getFrom();
        this.remoteJid = user1Accept.getTo();

        return new JingleIQ[] { user1Invite, user1Accept };
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
                myJid, remoteJid, sid, contents);

        mockConnection.sendPacket(transportInfoIq);

        return transportInfoIq;
    }

    private JingleIQ generateSessionAccept(
            JingleIQ sessionInit,
            Map<String, IceUdpTransportPacketExtension> transportMap)
    {
        //FIXME: we skip result packet

        JingleIQ accept = new JingleIQ();

        accept.setPacketID(Packet.nextID());

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

            synchronized (addSourceLock)
            {
                addSourceLock.notifyAll();
            }
        }
        else if (JingleAction.SOURCEREMOVE.equals(action))
        {
            MediaSSRCMap ssrcsToRemove
                = MediaSSRCMap.getSSRCsFromContent(
                    modifySSRcIq.getContentList());

            remoteSSRCs.remove(ssrcsToRemove);
        }
    }

    public List<SourcePacketExtension> getRemoteSSRCs(String media)
    {
        return remoteSSRCs.getSSRCsForMedia(media);
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
                Thread.currentThread().interrupt();
            }
        }
    }
}
