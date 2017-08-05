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
package org.jitsi.impl.protocol.xmpp;

import net.java.sip.communicator.impl.protocol.jabber.*;
import net.java.sip.communicator.service.protocol.*;
import net.java.sip.communicator.service.protocol.Message;
import net.java.sip.communicator.service.protocol.event.*;
import net.java.sip.communicator.util.Logger;

import org.jitsi.protocol.xmpp.*;
import org.jitsi.xmpp.util.*;

import org.jivesoftware.smack.*;
import org.jivesoftware.smack.SmackException.*;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smackx.muc.*;
import org.jivesoftware.smackx.muc.MultiUserChatException.*;
import org.jivesoftware.smackx.muc.packet.*;
import org.jivesoftware.smackx.xdata.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;
import org.jxmpp.jid.parts.*;
import org.jxmpp.stringprep.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * Stripped implementation of <tt>ChatRoom</tt> using Smack library.
 *
 * @author Pawel Domas
 */
public class ChatRoomImpl
    extends AbstractChatRoom
    implements ChatRoom2
{
    /**
     * The logger used by this class.
     */
    private final static Logger logger = Logger.getLogger(ChatRoomImpl.class);

    /**
     * Constant used to return empty presence list from
     * {@link #getPresenceExtensions()} in case there's no presence available.
     */
    private final static Collection<ExtensionElement>
        EMPTY_PRESENCE_LIST = Collections.emptyList();

    /**
     * Parent MUC operation set.
     */
    private final OperationSetMultiUserChatImpl opSet;

    /**
     * Caches early presence packets triggered by Smack, before there was member
     * joined event.
     */
    private Map<Jid, Presence> presenceCache = new HashMap<>();

    /**
     * Chat room name.
     */
    private final EntityBareJid roomName;

    /**
     * {@link MemberListener} instance.
     */
    private final MemberListener memberListener;

    private final ParticipantListener participantListener;

    private PresenceListener presenceInterceptor;

    /**
     * Smack multi user chat backend instance.
     */
    private MultiUserChat muc;

    /**
     * Our nickname.
     */
    private Resourcepart myNickName;

    /**
     * Our full Multi User Chat XMPP address.
     */
    private EntityFullJid myMucAddress;

    /**
     * Member presence listeners.
     */
    private CopyOnWriteArrayList<ChatRoomMemberPresenceListener> listeners
        = new CopyOnWriteArrayList<>();

    /**
     * Local user role listeners.
     */
    private CopyOnWriteArrayList<ChatRoomLocalUserRoleListener>
        localUserRoleListeners = new CopyOnWriteArrayList<>();

    /**
     * Nickname to member impl class map.
     */
    private final Map<EntityFullJid, ChatMemberImpl> members = new HashMap<>();

    /**
     * The list of <tt>ChatRoomMemberPropertyChangeListener</tt>.
     */
    private CopyOnWriteArrayList<ChatRoomMemberPropertyChangeListener>
        propListeners = new CopyOnWriteArrayList<>();

    /**
     * Local user role.
     */
    private ChatRoomMemberRole role;

    /**
     * Stores our last MUC presence packet for future update.
     */
    private Presence lastPresenceSent;

    /**
     * Number of participants in the chat room. That excludes the focus member.
     */
    private Integer participantNumber = 0;

    /**
     * Creates new instance of <tt>ChatRoomImpl</tt>.
     *
     * @param parentChatOperationSet parent multi user chat operation set.
     * @param roomName the name of the chat room that will be handled by
     *                 new <tt>ChatRoomImpl</tt>instance.
     */
    public ChatRoomImpl(OperationSetMultiUserChatImpl parentChatOperationSet,
                        EntityBareJid roomName)
    {
        this.opSet = parentChatOperationSet;
        this.roomName = roomName;

        MultiUserChatManager manager = MultiUserChatManager
                .getInstanceFor(parentChatOperationSet.getConnection());
        muc = manager.getMultiUserChat(roomName);

        this.memberListener = new MemberListener();
        muc.addParticipantStatusListener(memberListener);

        this.participantListener = new ParticipantListener();
        muc.addParticipantListener(participantListener);
    }

    @Override
    public String getName()
    {
        return roomName.toString();
    }

    @Override
    public EntityBareJid getNameAsJid()
    {
        return roomName;
    }

    @Override
    public String getIdentifier()
    {
        return null;
    }

    @Override
    public void join()
        throws OperationFailedException
    {
        joinAs(getParentProvider().getAccountID()
            .getAccountPropertyString(ProtocolProviderFactory.DISPLAY_NAME));
    }

    @Override
    public void join(byte[] password)
        throws OperationFailedException
    {
        join();
    }

    @Override
    public void joinAs(String nickname)
        throws OperationFailedException
    {
        try
        {
            this.myNickName = Resourcepart.from(nickname);
            this.myMucAddress = JidCreate.entityFullFrom(roomName, myNickName);

            this.presenceInterceptor = new PresenceListener()
            {
                @Override
                public void processPresence(Presence packet)
                {
                    lastPresenceSent = packet;
                }
            };
            muc.addPresenceInterceptor(presenceInterceptor);

            muc.create(myNickName);
            //muc.join(nickname);

            // Make the room non-anonymous, so that others can
            // recognize focus JID
            Form config = muc.getConfigurationForm();
            /*Iterator<FormField> fields = config.getFields();
            while (fields.hasNext())
            {
                FormField field = fields.next();
                logger.info("FORM: " + field.toXML());
            }*/
            Form answer = config.createAnswerForm();
            // Room non-anonymous
            FormField whois = new FormField("muc#roomconfig_whois");
            whois.addValue("anyone");
            answer.addField(whois);
            // Room moderated
            //FormField roomModerated
            //    = new FormField("muc#roomconfig_moderatedroom");
            //roomModerated.addValue("true");
            //answer.addField(roomModerated);
            // Only participants can send private messages
            //FormField onlyParticipantsPm
            //        = new FormField("muc#roomconfig_allowpm");
            //onlyParticipantsPm.addValue("participants");
            //answer.addField(onlyParticipantsPm);
            // Presence broadcast
            //FormField presenceBroadcast
            //        = new FormField("muc#roomconfig_presencebroadcast");
            //presenceBroadcast.addValue("participant");
            //answer.addField(presenceBroadcast);
            // Get member list
            //FormField getMemberList
            //        = new FormField("muc#roomconfig_getmemberlist");
            //getMemberList.addValue("participant");
            //answer.addField(getMemberList);
            // Public logging
            //FormField publicLogging
            //        = new FormField("muc#roomconfig_enablelogging");
            //publicLogging.addValue("false");
            //answer.addField(publicLogging);

            muc.sendConfigurationForm(answer);
        }
        catch (XMPPException
                | XmppStringprepException
                | MucAlreadyJoinedException
                | NotAMucServiceException
                | NoResponseException
                | NotConnectedException
                | MissingMucCreationAcknowledgeException
                | InterruptedException e)
        {
            throw new OperationFailedException(
                "Failed to join the room",
                OperationFailedException.GENERAL_ERROR, e);
        }
    }

    @Override
    public void joinAs(String nickname, byte[] password)
        throws OperationFailedException
    {
        joinAs(nickname);
    }

    @Override
    public boolean isJoined()
    {
        return muc.isJoined();
    }

    private void leave(String reason, EntityBareJid jid)
    {
        logger.info("Leave, reason: " + reason + " alt-jid: " + jid);

        leave();
    }

    @Override
    public void leave()
    {
        XMPPConnection connection = opSet.getConnection();
        if (connection != null && connection.isConnected())
        {
            try
            {
                muc.leave();
            }
            catch (NotConnectedException | InterruptedException e)
            {
                logger.error("Failed to properly leave " + muc.toString(), e);
            }
        }

        // Simulate member left events
        // No need to do this - we dispose whole conference anyway on stop
        /*HashMap<String, ChatMemberImpl> membersCopy;
        synchronized (members)
        {
            membersCopy
                = new HashMap<String, ChatMemberImpl>(members);
        }

        for (ChatMemberImpl member : membersCopy.values())
        {
            memberListener.left(member.getContactAddress());
        }*/

        /*
        FIXME: we do not care about local user left for now
        opSetMuc.fireLocalUserPresenceEvent(
                this,
                LocalUserChatRoomPresenceChangeEvent.LOCAL_USER_LEFT,
                reason,
                alternateAddress);*/

        // FIXME smack4: requires Smack#159
//        if (presenceInterceptor != null)
//        {
//            muc.removePresenceInterceptor(presenceInterceptor);
//        }

        muc.removeParticipantStatusListener(memberListener);

        if (connection != null && connection.isConnected())
            muc.removeParticipantListener(participantListener);

        // FIXME smack4: there used to be a custom dispose() method
        // if leave() fails, the might still be some listeners lingering around
        // muc.dispose();

        opSet.removeRoom(this);
    }

    @Override
    public String getSubject()
    {
        return muc.getSubject();
    }

    @Override
    public void setSubject(String subject)
        throws OperationFailedException
    {

    }

    @Override
    public String getUserNickname()
    {
        return myNickName.toString();
    }

    @Override
    public ChatRoomMemberRole getUserRole()
    {
        if(this.role == null)
        {
            Occupant o = muc.getOccupant(myMucAddress);

            if(o == null)
                return null;
            else
                this.role = ChatRoomJabberImpl.smackRoleToScRole(
                    o.getRole(), o.getAffiliation());
        }

        return this.role;
    }

    /**
     * Resets cached role instance so that it will be refreshed when {@link
     * #getUserRole()} is called.
     */
    private void resetCachedUserRole()
    {
        role = null;
    }

    /**
     * Resets cached role instance for given participant.
     * @param participant full mucJID of the participant for whom we want to
     *                    reset cached role instance.
     */
    private void resetRoleForParticipant(EntityFullJid participant)
    {
        if (participant.getResourcepart().equals(myNickName))
        {
            resetCachedUserRole();
        }
        else
        {
            ChatMemberImpl member = members.get(participant);
            if (member != null)
            {
                member.resetCachedRole();
            }
            else
            {
                logger.error(
                    "Role reset for: " + participant + " who does not exist");
            }
        }
    }

    @Override
    public void setLocalUserRole(ChatRoomMemberRole role)
        throws OperationFailedException
    {
        // Method not used but log error just in case to spare debugging
        logger.error("setLocalUserRole not implemented");
    }

    /**
     * Creates the corresponding ChatRoomLocalUserRoleChangeEvent and notifies
     * all <tt>ChatRoomLocalUserRoleListener</tt>s that local user's role has
     * been changed in this <tt>ChatRoom</tt>.
     *
     * @param previousRole the previous role that local user had
     * @param newRole the new role the local user gets
     * @param isInitial if <tt>true</tt> this is initial role set.
     */
    private void fireLocalUserRoleEvent(ChatRoomMemberRole previousRole,
                                        ChatRoomMemberRole newRole,
                                        boolean isInitial)
    {
        ChatRoomLocalUserRoleChangeEvent evt
            = new ChatRoomLocalUserRoleChangeEvent(
            this, previousRole, newRole, isInitial);

        if (logger.isTraceEnabled())
            logger.trace("Will dispatch the following ChatRoom event: " + evt);

        for (ChatRoomLocalUserRoleListener listener : localUserRoleListeners)
            listener.localUserRoleChanged(evt);
    }

    /**
     * Sets the new role for the local user in the context of this chat room.
     *
     * @param role the new role to be set for the local user
     * @param isInitial if <tt>true</tt> this is initial role set.
     */
    public void setLocalUserRole(ChatRoomMemberRole role, boolean isInitial)
    {
        fireLocalUserRoleEvent(getUserRole(), role, isInitial);
        this.role = role;
    }

    @Override
    public void setUserNickname(String nickname)
        throws OperationFailedException
    {

    }

    @Override
    public void addMemberPresenceListener(
        ChatRoomMemberPresenceListener listener)
    {
        listeners.add(listener);
    }

    @Override
    public void removeMemberPresenceListener(
        ChatRoomMemberPresenceListener listener)
    {
        listeners.remove(listener);
    }

    @Override
    public void addLocalUserRoleListener(ChatRoomLocalUserRoleListener listener)
    {
        localUserRoleListeners.add(listener);
    }

    @Override
    public void removelocalUserRoleListener(
        ChatRoomLocalUserRoleListener listener)
    {
        localUserRoleListeners.remove(listener);
    }

    @Override
    public void addMemberRoleListener(ChatRoomMemberRoleListener listener)
    {

    }

    @Override
    public void removeMemberRoleListener(ChatRoomMemberRoleListener listener)
    {

    }

    @Override
    public void addPropertyChangeListener(
        ChatRoomPropertyChangeListener listener)
    {

    }

    @Override
    public void removePropertyChangeListener(
        ChatRoomPropertyChangeListener listener)
    {

    }

    @Override
    public void addMemberPropertyChangeListener(
        ChatRoomMemberPropertyChangeListener listener)
    {
        propListeners.add(listener);
    }

    @Override
    public void removeMemberPropertyChangeListener(
        ChatRoomMemberPropertyChangeListener listener)
    {
        propListeners.remove(listener);
    }

    @Override
    public void invite(String userAddress, String reason)
    {

    }

    @Override
    public List<ChatRoomMember> getMembers()
    {
        synchronized (members)
        {
            return new ArrayList<ChatRoomMember>(members.values());
        }
    }

    @Override
    public XmppChatMember findChatMember(Jid mucJid)
    {
        ArrayList<ChatMemberImpl> copy;
        synchronized (members)
        {
            copy = new ArrayList<>(members.values());
        }

        for (ChatMemberImpl member : copy)
        {
            if (member.getJabberID().equals(mucJid))
            {
                return member;
            }
        }

        return null;
    }

    @Override
    public EntityFullJid getLocalMucJid()
    {
        return myMucAddress;
    }

    @Override
    public int getMembersCount()
    {
        return muc.getOccupantsCount();
    }

    @Override
    public void addMessageListener(ChatRoomMessageListener listener)
    {

    }

    @Override
    public void removeMessageListener(ChatRoomMessageListener listener)
    {

    }

    @Override
    public Message createMessage(byte[] content, String contentType,
                                 String contentEncoding, String subject)
    {
        return null;
    }

    @Override
    public Message createMessage(String messageText)
    {
        return null;
    }

    @Override
    public void sendMessage(Message message)
        throws OperationFailedException
    {

    }

    @Override
    public ProtocolProviderService getParentProvider()
    {
        return opSet.getProtocolProvider();
    }

    @Override
    public Iterator<ChatRoomMember> getBanList()
        throws OperationFailedException
    {
        return null;
    }

    @Override
    public void banParticipant(ChatRoomMember chatRoomMember, String reason)
        throws OperationFailedException
    {

    }

    @Override
    public void kickParticipant(ChatRoomMember chatRoomMember, String reason)
        throws OperationFailedException
    {

    }

    @Override
    public ChatRoomConfigurationForm getConfigurationForm()
        throws OperationFailedException
    {
        return null;
    }

    @Override
    public boolean isSystem()
    {
        return false;
    }

    @Override
    public boolean isPersistent()
    {
        return false;
    }

    @Override
    public Contact getPrivateContactByNickname(String name)
    {
        return null;
    }

    @Override
    public void grantAdmin(String address)
    {
        try
        {
            muc.grantAdmin(JidCreate.from(address));
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void grantMembership(String address)
    {
        try
        {
            muc.grantMembership(JidCreate.from(address));
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void grantModerator(String nickname)
    {
        try
        {
            muc.grantModerator(Resourcepart.from(nickname));
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void grantOwnership(String address)
    {
        logger.debug("Grant owner to " + address);

        // Have to construct the IQ manually as Smack version used here seems
        // to be using wrong namespace(muc#owner instead of muc#admin)
        // which does not work with the Prosody.
        MUCAdmin admin = new MUCAdmin();
        admin.setType(IQ.Type.set);
        admin.setTo(roomName);

        Jid jidAddress;
        try
        {
            jidAddress = JidCreate.from(address);
        }
        catch (XmppStringprepException e)
        {
            throw new RuntimeException(e);
        }

        MUCItem item = new MUCItem(MUCAffiliation.owner, jidAddress);
        admin.addItem(item);

        XmppProtocolProvider provider
                = (XmppProtocolProvider) getParentProvider();
        XmppConnection connection
                = provider.getConnectionAdapter();

        try
        {
            IQ reply = (IQ) connection.sendPacketAndGetReply(admin);
            if (reply == null || reply.getType() != IQ.Type.result)
            {
                // FIXME: we should have checked exceptions for all operations
                // in ChatRoom interface which are expected to fail.
                // OperationFailedException maybe ?
                throw new RuntimeException(
                    "Failed to grant owner: " + IQUtils.responseToXML(reply));
            }
        }
        catch (OperationFailedException e)
        {
            // XXX FIXME unable to throw OperationFailedException, because of
            // the ChatRoom interface
            throw new RuntimeException(
                "Failed to grant owner - XMPP disconnected", e);
        }
    }

    @Override
    public void grantVoice(String nickname)
    {

    }

    @Override
    public void revokeAdmin(String address)
    {

    }

    @Override
    public void revokeMembership(String address)
    {

    }

    @Override
    public void revokeModerator(String nickname)
    {

    }

    @Override
    public void revokeOwnership(String address)
    {

    }

    @Override
    public void revokeVoice(String nickname)
    {

    }

    @Override
    public ConferenceDescription publishConference(ConferenceDescription cd,
                                                   String name)
    {
        return null;
    }

    @Override
    public void updatePrivateContactPresenceStatus(String nickname)
    {

    }

    @Override
    public void updatePrivateContactPresenceStatus(Contact contact)
    {

    }

    @Override
    public boolean destroy(String reason, String alternateAddress)
    {
        try
        {
            muc.destroy(reason, JidCreate.entityBareFrom(alternateAddress));
        }
        catch (XMPPException
                | XmppStringprepException
                | InterruptedException
                | NoResponseException
                | NotConnectedException e)
        {
            //FIXME: should not be runtime, but OperationFailed is not
            // included in interface signature(see also other methods
            // catching XMPPException in this class)
            throw new RuntimeException(e);
        }
        return false;
    }

    @Override
    public List<String> getMembersWhiteList()
    {
        return null;
    }

    @Override
    public void setMembersWhiteList(List<String> members)
    {

    }

    private void notifyParticipantJoined(ChatMemberImpl member)
    {
        ChatRoomMemberPresenceChangeEvent event
            = new ChatRoomMemberPresenceChangeEvent(
                    this, member,
                    ChatRoomMemberPresenceChangeEvent.MEMBER_JOINED, null);

        for (ChatRoomMemberPresenceListener l : listeners)
        {
            l.memberPresenceChanged(event);
        }
    }

    private void notifyParticipantLeft(ChatMemberImpl member)
    {
        ChatRoomMemberPresenceChangeEvent event
            = new ChatRoomMemberPresenceChangeEvent(
                    this, member,
                    ChatRoomMemberPresenceChangeEvent.MEMBER_LEFT, null);

        for (ChatRoomMemberPresenceListener l : listeners)
        {
            l.memberPresenceChanged(event);
        }
    }

    private void notifyParticipantKicked(ChatMemberImpl member)
    {
        ChatRoomMemberPresenceChangeEvent event
            = new ChatRoomMemberPresenceChangeEvent(
                    this, member,
                    ChatRoomMemberPresenceChangeEvent.MEMBER_KICKED, null);

        for (ChatRoomMemberPresenceListener l : listeners)
        {
            l.memberPresenceChanged(event);
        }
    }

    private void notifyMemberPropertyChanged(ChatMemberImpl member)
    {
        ChatRoomMemberPropertyChangeEvent event
            = new ChatRoomMemberPropertyChangeEvent(
                    member, this,
                    ChatRoomMemberPropertyChangeEvent.MEMBER_PRESENCE,
                    null, member.getPresence());

        for (ChatRoomMemberPropertyChangeListener l : propListeners)
        {
            l.chatRoomPropertyChanged(event);
        }
    }

    public Occupant getOccupant(ChatMemberImpl chatMemeber)
    {
        return muc.getOccupant(
                chatMemeber.getJabberID().asEntityFullJidOrThrow());
    }

    /**
     * Returns the MUCUser packet extension included in the packet or
     * <tt>null</tt> if none.
     *
     * @param packet the packet that may include the MUCUser extension.
     * @return the MUCUser found in the packet.
     */
    private MUCUser getMUCUserExtension(Presence packet)
    {
        if (packet != null)
        {
            // Get the MUC User extension
            return (MUCUser) packet.getExtension(
                "x", "http://jabber.org/protocol/muc#user");
        }
        return null;
    }

    public void setPresenceExtension(ExtensionElement extension,
                                     boolean          remove)
    {
        if (lastPresenceSent == null)
        {
            logger.error("No presence packet obtained yet");
            return;
        }

        XmppProtocolProvider xmppProtocolProvider
            = (XmppProtocolProvider) getParentProvider();

        // Remove old
        ExtensionElement old
            = lastPresenceSent.getExtension(
                    extension.getElementName(), extension.getNamespace());
        if (old != null)
        {
            lastPresenceSent.removeExtension(old);
        }

        if (!remove)
        {
            // Add new
            lastPresenceSent.addExtension(extension);
        }

        XmppConnection connection = xmppProtocolProvider.getConnectionAdapter();
        if (connection == null)
        {
            logger.error("Failed to send presence extension - no connection");
            return;
        }

        connection.sendStanza(lastPresenceSent);
    }

    /**
     * {@inheritDoc}
     */
    public Collection<ExtensionElement> getPresenceExtensions()
    {
        return lastPresenceSent != null
            ? new ArrayList<>(lastPresenceSent.getExtensions())
            : EMPTY_PRESENCE_LIST;
    }

    /**
     * {@inheritDoc}
     */
    public void modifyPresence(Collection<ExtensionElement> toRemove,
                               Collection<ExtensionElement> toAdd)
    {
        if (lastPresenceSent == null)
        {
            logger.error("No presence packet obtained yet");
            return;
        }

        XmppProtocolProvider xmppProtocolProvider
            = (XmppProtocolProvider) getParentProvider();

        // Remove old
        if (toRemove != null)
        {
            for (ExtensionElement old : toRemove)
            {
                lastPresenceSent.removeExtension(old);
            }
        }

        // Add new
        if (toAdd != null)
        {
            for (ExtensionElement newExt : toAdd)
            {
                lastPresenceSent.addExtension(newExt);
            }
        }

        XmppConnection connection = xmppProtocolProvider.getConnectionAdapter();
        if (connection == null)
        {
            logger.error("Failed to send presence extension - no connection");
            return;
        }

        connection.sendStanza(lastPresenceSent);
    }

    private ChatMemberImpl addMember(EntityFullJid participant)
    {
        ChatMemberImpl newMember;

        synchronized (members)
        {
            if (members.containsKey(participant))
            {
                logger.error(participant + " already in " + roomName);
                return null;
            }

            if(!participant.equals(myMucAddress))
            {
                participantNumber++;
            }

            newMember
                = new ChatMemberImpl(participant, ChatRoomImpl.this,
                        participantNumber);

            members.put(participant, newMember);
        }

        return newMember;
    }

    Jid getMemberJid(EntityFullJid mucAddress)
    {
        Occupant occupant = muc.getOccupant(mucAddress);
        if (occupant == null)
        {
            logger.error("Unable to get occupant for " + mucAddress);
            return null;
        }
        return occupant.getJid();
    }

    class MemberListener
        implements ParticipantStatusListener
    {
        @Override
        public void joined(EntityFullJid mucJid)
        {
            synchronized (members)
            {
                if (logger.isDebugEnabled())
                {
                    logger.debug("Joined " + mucJid + " room: " + roomName);
                }
                //logger.info(Thread.currentThread()+"JOINED ROOM: "+participant);

                ChatMemberImpl member = addMember(mucJid);
                if (member == null)
                {
                    logger.error("member is NULL");
                    return;
                }

                // Process any cached presence
                XmppProtocolProvider protocolProvider
                    = (XmppProtocolProvider) getParentProvider();

                XMPPConnection connection = protocolProvider.getConnection();
                if (connection == null)
                {
                    logger.error("Connection is NULL");
                    return;
                }

                // Process presence cached in the roster to init fields
                // like video muted etc.
                Presence cachedPresence = presenceCache.get(mucJid);
                if (cachedPresence != null)
                {
                    member.processPresence(cachedPresence);
                }

                // Trigger participant "joined"
                notifyParticipantJoined(member);

                // Fire presence event after "joined" event
                if (cachedPresence != null)
                {
                    notifyMemberPropertyChanged(member);
                }
            }
        }

        private ChatMemberImpl removeMember(EntityFullJid participant)
        {
            synchronized (members)
            {
                ChatMemberImpl removed = members.remove(participant);

                if (removed == null)
                    logger.error(participant + " not in " + roomName);

                participantNumber--;

                return removed;
            }
        }

        @Override
        public void left(EntityFullJid participant)
        {
            synchronized (members)
            {
                if (logger.isDebugEnabled())
                {
                    logger.debug("Left " + participant + " room: " + roomName);
                }

                ChatMemberImpl member = removeMember(participant);

                if (member != null)
                {
                    notifyParticipantLeft(member);
                }
                else
                {
                    logger.warn(
                        "Member left event for non-existing participant: "
                                    + participant);
                }

                // Clear cached Presence
                presenceCache.remove(participant);
            }
        }

        @Override
        public void kicked(EntityFullJid participant, Jid actor, String reason)
        {
            synchronized (members)
            {
                if (logger.isDebugEnabled())
                {
                    logger.debug(
                        "Kicked: " + participant + ", "
                                + actor + ", " + reason);
                }

                ChatMemberImpl member = removeMember(participant);

                if (member == null)
                {
                    logger.error(
                        "Kicked participant does not exist: " + participant);
                    return;
                }

                notifyParticipantKicked(member);
            }
        }

        @Override
        public void voiceGranted(EntityFullJid s)
        {
            if (logger.isTraceEnabled())
                logger.trace("Voice granted: " + s);

            // We do not fire events - not required for now
            resetRoleForParticipant(s);
        }

        @Override
        public void voiceRevoked(EntityFullJid s)
        {
            if (logger.isTraceEnabled())
                logger.trace("Voice revoked: " + s);

            // We do not fire events - not required for now
            resetRoleForParticipant(s);
        }

        @Override
        public void banned(EntityFullJid s, Jid actor, String reason)
        {
            if (logger.isTraceEnabled())
                logger.trace("Banned: " + s + ", " + actor + ", " + reason);

            // We do not fire events - not required for now
            resetRoleForParticipant(s);
        }

        @Override
        public void membershipGranted(EntityFullJid s)
        {
            if (logger.isTraceEnabled())
                logger.trace("Membership granted: " + s);

            // We do not fire events - not required for now
            resetRoleForParticipant(s);
        }

        @Override
        public void membershipRevoked(EntityFullJid s)
        {
            if (logger.isTraceEnabled())
                logger.trace("Membership revoked: " + s);

            // We do not fire events - not required for now
            resetRoleForParticipant(s);
        }

        @Override
        public void moderatorGranted(EntityFullJid s)
        {
            if (logger.isTraceEnabled())
                logger.trace("Moderator granted: " + s);

            // We do not fire events - not required for now
            resetRoleForParticipant(s);
        }

        @Override
        public void moderatorRevoked(EntityFullJid s)
        {
            if (logger.isTraceEnabled())
                logger.trace("Moderator revoked: " + s);

            // We do not fire events - not required for now
            resetRoleForParticipant(s);
        }

        @Override
        public void ownershipGranted(EntityFullJid s)
        {
            if (logger.isTraceEnabled())
                logger.trace("Ownership granted: " + s);

            // We do not fire events - not required for now
            resetRoleForParticipant(s);
        }

        @Override
        public void ownershipRevoked(EntityFullJid s)
        {
            if (logger.isTraceEnabled())
                logger.trace("Ownership revoked: " + s);

            // We do not fire events - not required for now
            resetRoleForParticipant(s);
        }

        @Override
        public void adminGranted(EntityFullJid s)
        {
            if (logger.isTraceEnabled())
                logger.trace("Admin granted: " + s);

            // We do not fire events - not required for now
            resetRoleForParticipant(s);
        }

        @Override
        public void adminRevoked(EntityFullJid s)
        {
            if (logger.isTraceEnabled())
                logger.trace("Admin revoked: " + s);

            // We do not fire events - not required for now
            resetRoleForParticipant(s);
        }

        @Override
        public void nicknameChanged(EntityFullJid oldNickname,
                                    Resourcepart newNickname)
        {
            logger.error("nicknameChanged - NOT IMPLEMENTED");
            /*synchronized (members)
            {
                removeMember(oldNickname);

                addMember(newNickname);
            }*/
        }
    }

    class ParticipantListener
        implements PresenceListener
    {
        /**
         * Processes an incoming presence packet.
         * @param presence the incoming presence.
         */
        @Override
        public void processPresence(Presence presence)
        {
            if (presence == null || presence.getError() != null)
            {
                logger.warn("Unable to handle packet: " +
                            (presence == null ? "null" : presence.toXML()));
                return;
            }

            if (logger.isTraceEnabled())
            {
                logger.trace("Presence received " + presence.toXML());
            }

            // Should never happen, but log if something is broken
            if (myMucAddress == null)
            {
                logger.error(
                    "Processing presence when we're not aware of our address");
            }

            if (myMucAddress != null && myMucAddress.equals(presence.getFrom()))
                processOwnPresence(presence);
            else
                processOtherPresence(presence);
        }

        /**
         * Processes a <tt>Presence</tt> packet addressed to our own occupant
         * JID.
         * @param presence the packet to process.
         */
        private void processOwnPresence(Presence presence)
        {
            MUCUser mucUser = getMUCUserExtension(presence);

            if (mucUser != null)
            {
                MUCAffiliation affiliation = mucUser.getItem().getAffiliation();
                MUCRole role = mucUser.getItem().getRole();

                // this is the presence for our member initial role and
                // affiliation, as smack do not fire any initial
                // events lets check it and fire events
                ChatRoomMemberRole jitsiRole =
                    ChatRoomJabberImpl.smackRoleToScRole(role, affiliation);

                if(!presence.isAvailable()
                    && MUCAffiliation.none == affiliation
                    && MUCRole.none == role)
                {
                    Destroy destroy = mucUser.getDestroy();
                    if(destroy == null)
                    {
                        // the room is unavailable to us, there is no
                        // message we will just leave
                        leave();
                    }
                    else
                    {
                        leave(destroy.getReason(), destroy.getJid());
                    }
                }
                else
                {
                    setLocalUserRole(
                        jitsiRole, ChatRoomImpl.this.role == null);
                }
            }
        }

        /**
         * Process a <tt>Presence</tt> packet sent by one of the other room
         * occupants.
         */
        private void processOtherPresence(Presence presence)
        {
            ChatMemberImpl chatMember
                = (ChatMemberImpl) findChatMember(presence.getFrom());

            if (chatMember != null)
            {
                chatMember.processPresence(presence);

                notifyMemberPropertyChanged(chatMember);
            }
            else
            {
                // We want to cache that Presence for "on member joined" event
                if (presence.getType().equals(Presence.Type.available))
                {
                    logger.warn(
                            "Presence for not existing member: "
                                + presence.toXML());

                    // Note that this access to #presenceCache is not protected
                    // by a lock on #members (as opposed to the other places
                    // where #precenceCache is accessed).
                    presenceCache.put(presence.getFrom(), presence);
                }
            }
        }
    }
}
