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

import org.jitsi.jicofo.*;
import org.jitsi.protocol.xmpp.*;
import org.jitsi.utils.logging.*;
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
    implements ChatRoom2, PresenceListener
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
     * The room JID (e.g. "room@service").
     */
    private final EntityBareJid roomJid;

    /**
     * {@link MemberListener} instance.
     */
    private final MemberListener memberListener = new MemberListener();

    /**
     * Listener for presence that smack sends on our behalf.
     */
    private PresenceListener presenceInterceptor;

    /**
     * Smack multi user chat backend instance.
     */
    private MultiUserChat muc;

    /**
     * The resource part of our occupant JID.
     */
    private Resourcepart myResourcepart;

    /**
     * Our full Multi User Chat XMPP address.
     */
    private EntityFullJid myOccupantJid;

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
     * Number of members in the chat room. That excludes the focus member.
     */
    private Integer memberCount = 0;

    /** The conference that is backed by this MUC room. */
    private JitsiMeetConference conference;

    /**
     * Creates new instance of <tt>ChatRoomImpl</tt>.
     *
     * @param parentChatOperationSet parent multi user chat operation set.
     * @param roomJid the room JID (e.g. "room@service").
     */
    public ChatRoomImpl(OperationSetMultiUserChatImpl parentChatOperationSet,
                        EntityBareJid roomJid)
    {
        this.opSet = parentChatOperationSet;
        this.roomJid = roomJid;

        MultiUserChatManager manager = MultiUserChatManager
                .getInstanceFor(parentChatOperationSet.getConnection());
        muc = manager.getMultiUserChat(this.roomJid);

        muc.addParticipantStatusListener(memberListener);
        muc.addParticipantListener(this);
    }

    /**
     * Sets the conference that is backed by this MUC. Can only be set once.
     * @param conference the conference backed by this MUC.
     */
    public void setConference(JitsiMeetConference conference)
    {
        if (this.conference != null && conference != null)
        {
            throw new IllegalStateException("Conference is already set!");
        }

        this.conference = conference;
    }

    void setStartMuted(boolean[] startMuted)
    {
        if (conference == null)
        {
            logger.warn("Can not set 'start muted', conference is null.");
            return;
        }

        conference.setStartMuted(startMuted);
    }

    @Override
    public String getName()
    {
        return roomJid.toString();
    }

    @Override
    public EntityBareJid getRoomJid()
    {
        return roomJid;
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
            this.myResourcepart = Resourcepart.from(nickname);
            this.myOccupantJid = JidCreate.entityFullFrom(roomJid,
                                                          myResourcepart);

            this.presenceInterceptor = new PresenceListener()
            {
                @Override
                public void processPresence(Presence packet)
                {
                    lastPresenceSent = packet;
                }
            };
            muc.addPresenceInterceptor(presenceInterceptor);

            muc.createOrJoin(myResourcepart);

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
            String whoisFieldName = "muc#roomconfig_whois";
            FormField whois = answer.getField(whoisFieldName);
            if (whois == null)
            {
                whois = new FormField(whoisFieldName);
                answer.addField(whois);
            }

            whois.addValue("anyone");
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
        return muc != null && muc.isJoined();
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
        if (connection != null)
        {
            try
            {
                // FIXME smack4: there used to be a custom dispose() method
                // if leave() fails, there might still be some listeners
                // lingering around
                muc.leave();
            }
            catch (NotConnectedException | InterruptedException e)
            {
                // when the connection is not connected and
                // we get NotConnectedException, this is expected (skip log)
                if (connection.isConnected()
                    || e instanceof InterruptedException)
                {
                    logger.error(
                        "Failed to properly leave " + muc.toString(), e);
                }
            }
            finally
            {
                if (presenceInterceptor != null)
                {
                    muc.removePresenceInterceptor(presenceInterceptor);
                }

                if (memberListener != null)
                {
                    muc.removeParticipantStatusListener(memberListener);
                }

                muc.removeParticipantListener(this);

                opSet.removeRoom(this);
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
        return myResourcepart.toString();
    }

    @Override
    public ChatRoomMemberRole getUserRole()
    {
        if (this.role == null)
        {
            Occupant o = muc.getOccupant(myOccupantJid);

            if (o == null)
            {
                return null;
            }
            else
            {
                this.role
                    = ChatRoomJabberImpl.smackRoleToScRole(
                        o.getRole(),
                        o.getAffiliation());
            }
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
     * Resets cached role instance for given occupantJid.
     * @param occupantJid full mucJID of the occupant for whom we want to
     * reset the cached role instance.
     */
    private void resetRoleForOccupant(EntityFullJid occupantJid)
    {
        if (occupantJid.getResourcepart().equals(myResourcepart))
        {
            resetCachedUserRole();
        }
        else
        {
            ChatMemberImpl member = members.get(occupantJid);
            if (member != null)
            {
                member.resetCachedRole();
            }
            else
            {
                logger.error(
                    "Role reset for: " + occupantJid + " who does not exist");
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
        {
            logger.trace("Will dispatch the following ChatRoom event: " + evt);
        }

        localUserRoleListeners
            .forEach(listener -> listener.localUserRoleChanged(evt));
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
            return new ArrayList<>(members.values());
        }
    }

    @Override
    public XmppChatMember findChatMember(Jid occupantJid)
    {
        if (occupantJid == null)
        {
            return null;
        }

        synchronized (members)
        {
            for (ChatMemberImpl member : members.values())
            {
                if (occupantJid.equals(member.getOccupantJid()))
                {
                    return member;
                }
            }
        }

        return null;
    }

    @Override
    public EntityFullJid getLocalOccupantJid()
    {
        return myOccupantJid;
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

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean containsPresenceExtension(String elementName,
                                             String namespace)
    {
        return lastPresenceSent != null
            && lastPresenceSent.getExtension(elementName, namespace) != null;
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
        admin.setTo(roomJid);

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
            muc.destroy(reason,
                alternateAddress != null ?
                    JidCreate.entityBareFrom(alternateAddress) : null);
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

    private void notifyMemberJoined(ChatMemberImpl member)
    {
        ChatRoomMemberPresenceChangeEvent event
            = new ChatRoomMemberPresenceChangeEvent(
                    this, member,
                    ChatRoomMemberPresenceChangeEvent.MEMBER_JOINED, null);

        listeners.forEach(l -> l.memberPresenceChanged(event));
    }

    private void notifyMemberLeft(ChatMemberImpl member)
    {
        ChatRoomMemberPresenceChangeEvent event
            = new ChatRoomMemberPresenceChangeEvent(
                    this, member,
                    ChatRoomMemberPresenceChangeEvent.MEMBER_LEFT, null);

        listeners.forEach(l -> l.memberPresenceChanged(event));
    }

    private void notifyMemberKicked(ChatMemberImpl member)
    {
        ChatRoomMemberPresenceChangeEvent event
            = new ChatRoomMemberPresenceChangeEvent(
                    this, member,
                    ChatRoomMemberPresenceChangeEvent.MEMBER_KICKED, null);

        listeners.forEach(l -> l.memberPresenceChanged(event));
    }

    private void notifyMemberPropertyChanged(ChatMemberImpl member)
    {
        ChatRoomMemberPropertyChangeEvent event
            = new ChatRoomMemberPropertyChangeEvent(
                    member, this,
                    ChatRoomMemberPropertyChangeEvent.MEMBER_PRESENCE,
                    null, member.getPresence());

        propListeners.forEach(l -> l.chatRoomPropertyChanged(event));
    }

    Occupant getOccupant(ChatMemberImpl chatMember)
    {
        return muc.getOccupant(chatMember.getOccupantJid());
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

        // Reset the stanza ID before sending
        lastPresenceSent.setStanzaId(null);

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
            toRemove.forEach(old -> lastPresenceSent.removeExtension(old));
        }

        // Add new
        if (toAdd != null)
        {
            toAdd.forEach(newExt -> lastPresenceSent.addExtension(newExt));
        }

        XmppConnection connection = xmppProtocolProvider.getConnectionAdapter();
        if (connection == null)
        {
            logger.error("Failed to send presence extension - no connection");
            return;
        }

        // Reset the stanza ID before sending
        lastPresenceSent.setStanzaId(null);

        connection.sendStanza(lastPresenceSent);
    }

    /**
     * Adds a new {@link ChatMemberImpl} with the given JID to {@link #members}.
     * If a member with the given JID already exists, it returns the existing
     * instance.
     * @param jid the JID of the member.
     * @return the {@link ChatMemberImpl} for the member with the given JID.
     */
    private ChatMemberImpl addMember(EntityFullJid jid)
    {
        synchronized (members)
        {
            if (members.containsKey(jid))
            {
                return members.get(jid);
            }

            if (!jid.equals(myOccupantJid))
            {
                memberCount++;
            }

            ChatMemberImpl newMember
                = new ChatMemberImpl(jid, ChatRoomImpl.this, memberCount);

            members.put(jid, newMember);

            return newMember;
        }
    }

    /**
     * Gets the "real" JID of an occupant of this room specified by its
     * occupant JID.
     * @param occupantJid the occupant JID.
     * @return the "real" JID of the occupant, or {@code null}.
     */
    Jid getJid(EntityFullJid occupantJid)
    {
        Occupant occupant = muc.getOccupant(occupantJid);
        if (occupant == null)
        {
            logger.error("Unable to get occupant for " + occupantJid);
            return null;
        }
        return occupant.getJid();
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
            ChatRoomMemberRole jitsiRole
                = ChatRoomJabberImpl.smackRoleToScRole(role, affiliation);

            if (!presence.isAvailable()
                && MUCAffiliation.none == affiliation
                && MUCRole.none == role)
            {
                Destroy destroy = mucUser.getDestroy();
                if (destroy == null)
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
                setLocalUserRole(jitsiRole, ChatRoomImpl.this.role == null);
            }
        }
    }

    /**
     * Process a <tt>Presence</tt> packet sent by one of the other room
     * occupants.
     * @param presence the presence.
     */
    private void processOtherPresence(Presence presence)
    {
        EntityFullJid jid
            = presence.getFrom().asEntityFullJidIfPossible();
        if (jid == null)
        {
            logger.warn("Presence without a valid jid: "
                            + presence.getFrom());
            return;
        }

        ChatMemberImpl chatMember;
        boolean memberJoined = false;
        boolean memberLeft = false;

        synchronized (members)
        {
            chatMember = (ChatMemberImpl) findChatMember(jid);
            if (chatMember == null)
            {
                if (presence.getType().equals(Presence.Type.available))
                {
                    // This is how we detect that a new member has joined. We
                    // do not use the ParticipantStatusListener#joined callback.
                    if (logger.isDebugEnabled())
                    {
                        logger.debug("Joined " + jid + " room: " + roomJid);
                    }
                    chatMember = addMember(jid);
                    memberJoined = true;
                }
                else
                {
                    // We received presence from an unknown member which doesn't
                    // look like a new member's presence. Ignore it.
                    // The member might have been just removed via left(), which
                    // is fine.
                    return;
                }
            }
            else if (presence.getType().equals(Presence.Type.unavailable))
            {
                memberLeft = true;
            }
        }

        if (chatMember != null)
        {
            chatMember.processPresence(presence);

            if (memberJoined)
            {
                // Trigger member "joined"
                notifyMemberJoined(chatMember);
            }
            else if (memberLeft)
            {
                // In some cases smack fails to call left(). We'll call it here
                // any time we receive presence unavailable
                memberListener.left(jid);
            }

            if (!memberLeft)
            {
                notifyMemberPropertyChanged(chatMember);
            }
        }
    }

    /**
     * Processes an incoming presence packet.
     *
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
        if (myOccupantJid == null)
        {
            logger.error(
                "Processing presence when we're not aware of our address");
        }

        if (myOccupantJid != null && myOccupantJid.equals(presence.getFrom()))
        {
            processOwnPresence(presence);
        }
        else
        {
            processOtherPresence(presence);
        }
    }

    class MemberListener
        implements ParticipantStatusListener
    {
        @Override
        public void joined(EntityFullJid mucJid)
        {
            // When a new member joins, Smack seems to fire
            // ParticipantStatusListener#joined and
            // PresenceListener#processPresence in a non-deterministic order.

            // In order to ensure that we have all the information contained
            // in presence at the time that we create a new ChatMemberImpl,
            // we completely ignore this joined event. Instead, we rely on
            // processPresence to detect when a new member has joined and
            // trigger the creation of a ChatMemberImpl by calling
            // ChatRoomImpl#memberJoined()

            if (logger.isDebugEnabled())
            {
                logger.debug("Ignore a member joined event for " + mucJid);
            }
        }

        private ChatMemberImpl removeMember(EntityFullJid occupantJid)
        {
            synchronized (members)
            {
                ChatMemberImpl removed = members.remove(occupantJid);

                if (removed == null)
                {
                    logger.error(occupantJid + " not in " + roomJid);
                }

                memberCount--;

                return removed;
            }
        }

        /**
         * This needs to be prepared to run twice for the same member.
         * @param occupantJid
         */
        @Override
        public void left(EntityFullJid occupantJid)
        {
            ChatMemberImpl member;

            synchronized (members)
            {
                if (logger.isDebugEnabled())
                {
                    logger.debug("Left " + occupantJid + " room: " + roomJid);
                }

                member = removeMember(occupantJid);
            }

            if (member != null)
            {
                notifyMemberLeft(member);
            }
            else
            {
                logger.info(
                    "Member left event for non-existing member: "
                                + occupantJid);
            }
        }

        @Override
        public void kicked(EntityFullJid occupantJid, Jid actor, String reason)
        {
            ChatMemberImpl member;

            synchronized (members)
            {
                if (logger.isDebugEnabled())
                {
                    logger.debug(
                        "Kicked: " + occupantJid + ", "
                            + actor + ", " + reason);
                }

                member = removeMember(occupantJid);
            }

            if (member == null)
            {
                logger.error(
                    "Kicked member does not exist: " + occupantJid);
                return;
            }

            notifyMemberKicked(member);
        }

        @Override
        public void voiceGranted(EntityFullJid s)
        {
            if (logger.isTraceEnabled())
            {
                logger.trace("Voice granted: " + s);
            }

            // We do not fire events - not required for now
            resetRoleForOccupant(s);
        }

        @Override
        public void voiceRevoked(EntityFullJid s)
        {
            if (logger.isTraceEnabled())
            {
                logger.trace("Voice revoked: " + s);
            }

            // We do not fire events - not required for now
            resetRoleForOccupant(s);
        }

        @Override
        public void banned(EntityFullJid s, Jid actor, String reason)
        {
            if (logger.isTraceEnabled())
            {
                logger.trace("Banned: " + s + ", " + actor + ", " + reason);
            }

            // We do not fire events - not required for now
            resetRoleForOccupant(s);
        }

        @Override
        public void membershipGranted(EntityFullJid s)
        {
            if (logger.isTraceEnabled())
            {
                logger.trace("Membership granted: " + s);
            }

            // We do not fire events - not required for now
            resetRoleForOccupant(s);
        }

        @Override
        public void membershipRevoked(EntityFullJid s)
        {
            if (logger.isTraceEnabled())
            {
                logger.trace("Membership revoked: " + s);
            }

            // We do not fire events - not required for now
            resetRoleForOccupant(s);
        }

        @Override
        public void moderatorGranted(EntityFullJid s)
        {
            if (logger.isTraceEnabled())
            {
                logger.trace("Moderator granted: " + s);
            }

            // We do not fire events - not required for now
            resetRoleForOccupant(s);
        }

        @Override
        public void moderatorRevoked(EntityFullJid s)
        {
            if (logger.isTraceEnabled())
            {
                logger.trace("Moderator revoked: " + s);
            }

            // We do not fire events - not required for now
            resetRoleForOccupant(s);
        }

        @Override
        public void ownershipGranted(EntityFullJid s)
        {
            if (logger.isTraceEnabled())
            {
                logger.trace("Ownership granted: " + s);
            }

            // We do not fire events - not required for now
            resetRoleForOccupant(s);
        }

        @Override
        public void ownershipRevoked(EntityFullJid s)
        {
            if (logger.isTraceEnabled())
            {
                logger.trace("Ownership revoked: " + s);
            }

            // We do not fire events - not required for now
            resetRoleForOccupant(s);
        }

        @Override
        public void adminGranted(EntityFullJid s)
        {
            if (logger.isTraceEnabled())
            {
                logger.trace("Admin granted: " + s);
            }

            // We do not fire events - not required for now
            resetRoleForOccupant(s);
        }

        @Override
        public void adminRevoked(EntityFullJid s)
        {
            if (logger.isTraceEnabled())
            {
                logger.trace("Admin revoked: " + s);
            }

            // We do not fire events - not required for now
            resetRoleForOccupant(s);
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
}
