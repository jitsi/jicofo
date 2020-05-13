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
package mock.muc;

import net.java.sip.communicator.service.protocol.*;
import net.java.sip.communicator.service.protocol.Message;
import net.java.sip.communicator.service.protocol.event.*;

import org.jitsi.jicofo.*;
import org.jitsi.protocol.xmpp.*;

import org.jitsi.utils.logging.*;
import org.jivesoftware.smack.packet.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;
import org.jxmpp.jid.parts.*;
import org.jxmpp.stringprep.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * Mock {@link ChatRoom} implementation.
 *
 * @author Pawel Domas
 */
public class MockMultiUserChat
    extends AbstractChatRoom
    implements ChatRoom2
{
    /**
     * The logger
     */
    private static final Logger logger
        = Logger.getLogger(MockMultiUserChat.class);

    private final EntityBareJid roomName;

    private final ProtocolProviderService protocolProvider;

    private volatile boolean isJoined;

    private final List<ChatRoomMember> members = new CopyOnWriteArrayList<>();

    private XmppChatMember me;

    /**
     * Listeners that will be notified of changes in member status in the
     * room such as member joined, left or being kicked or dropped.
     */
    private final Vector<ChatRoomMemberPresenceListener> memberListeners
        = new Vector<>();

    private final Vector<ChatRoomLocalUserRoleListener> localUserRoleListeners
        = new Vector<>();

    private final Vector<ChatRoomMemberRoleListener> memberRoleListeners
        = new Vector<>();

    private JitsiMeetConference conference;

    public MockMultiUserChat(EntityBareJid roomName,
                             ProtocolProviderService protocolProviderService)
    {
        this.roomName = roomName;
        this.protocolProvider = protocolProviderService;
    }

    @Override
    public EntityFullJid getLocalOccupantJid()
    {
        return me != null ? me.getOccupantJid() : null;
    }

    @Override
    public Collection<ExtensionElement> getPresenceExtensions()
    {
        throw new RuntimeException("not implemented");
    }

    @Override
    public boolean containsPresenceExtension(
        String elementName, String namespace)
    {
        return false;
    }

    @Override
    public void modifyPresence(
        Collection<ExtensionElement> toRemove,
        Collection<ExtensionElement> toAdd)
    {
        throw new RuntimeException("not implemented");
    }

    @Override
    public void setConference(JitsiMeetConference conference)
    {
        this.conference = conference;
    }

    @Override
    public String getName()
    {
        return roomName.toString();
    }

    @Override
    public EntityBareJid getRoomJid()
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
        joinAs(nickname, null);
    }

    private EntityFullJid createAddressForName(String nickname)
            throws XmppStringprepException
    {
        return JidCreate.entityFullFrom(roomName, Resourcepart.from(nickname));
    }

    @Override
    public void joinAs(String nickname, byte[] password)
        throws OperationFailedException
    {
        if (isJoined)
            throw new OperationFailedException("Alread joined the room", 0);

        isJoined = true;

        MockRoomMember member;
        try
        {
            member = new MockRoomMember(createAddressForName(nickname), this);
        }
        catch (XmppStringprepException e)
        {
            throw new OperationFailedException(
                    "Invalid mock room member JID",
                    OperationFailedException.ILLEGAL_ARGUMENT,
                    e);
        }

        // FIXME: for mock purposes we are always the owner on join()
        boolean isOwner = true;//= members.size() == 0;

        synchronized (members)
        {
            members.add(member);

            me = member;

            fireMemberPresenceEvent(
                me, me, ChatRoomMemberPresenceChangeEvent.MEMBER_JOINED, null);
        }

        ChatRoomMemberRole oldRole = me.getRole();
        if (isOwner)
        {
            me.setRole(ChatRoomMemberRole.OWNER);
        }

        fireLocalUserRoleEvent(
            me, oldRole, true);
    }

    public MockRoomMember mockOwnerJoin(EntityFullJid name)
    {
        MockRoomMember member = new MockRoomMember(name, this);

        member.setRole(ChatRoomMemberRole.OWNER);

        mockJoin(member);

        return member;
    }

    public MockRoomMember mockJoin(String nickname)
            throws XmppStringprepException
    {
        return mockJoin(createMockRoomMember(nickname));
    }

    public MockRoomMember createMockRoomMember(String nickname)
            throws XmppStringprepException
    {
        return new MockRoomMember(
            createAddressForName(nickname), this);
    }

    public MockRoomMember mockJoin(MockRoomMember member)
    {
        synchronized (members)
        {
            Resourcepart name;
            try
            {
                name = Resourcepart.from(member.getName());
            }
            catch (XmppStringprepException e)
            {
                throw new IllegalArgumentException(
                        "The member name " + member.getName() + " is invalid");
            }

            if (findMember(name) != null)
            {
                throw new IllegalArgumentException(
                        "The member with name: " + name
                            + " is in the room already");
            }

            members.add(member);

            fireMemberPresenceEvent(
                    member, member,
                    ChatRoomMemberPresenceChangeEvent.MEMBER_JOINED, null);

            return member;
        }
    }

    public void mockLeave(String memberName)
    {
        for (ChatRoomMember member : members)
        {
            if (member.getName().equals(memberName))
            {
                mockLeave((MockRoomMember) member);
            }
        }
    }

    private void mockLeave(MockRoomMember member)
    {
        synchronized (members)
        {

            if (!members.remove(member))
            {
                throw new RuntimeException(
                        "Member is not in the room " + member);
            }

            fireMemberPresenceEvent(
                    member, member,
                    ChatRoomMemberPresenceChangeEvent.MEMBER_LEFT, null);
        }
    }

    @Override
    public boolean isJoined()
    {
        return isJoined;
    }

    @Override
    public void leave()
    {
        if (!isJoined)
            return;

        isJoined = false;

        synchronized (members)
        {
            members.remove(me);

            fireMemberPresenceEvent(
                me, me, ChatRoomMemberPresenceChangeEvent.MEMBER_LEFT, null);
        }

        me = null;
    }

    @Override
    public String getSubject()
    {
        return null;
    }

    @Override
    public void setSubject(String subject)
        throws OperationFailedException
    {

    }

    @Override
    public String getUserNickname()
    {
        return null;
    }

    @Override
    public ChatRoomMemberRole getUserRole()
    {
        return ChatRoomMemberRole.OWNER;
    }

    @Override
    public void setLocalUserRole(ChatRoomMemberRole role)
        throws OperationFailedException
    {

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
        synchronized (memberListeners)
        {
            memberListeners.add(listener);
        }
    }

    @Override
    public void removeMemberPresenceListener(
        ChatRoomMemberPresenceListener listener)
    {
        synchronized (memberListeners)
        {
            memberListeners.remove(listener);
        }
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
        memberRoleListeners.add(listener);
    }

    @Override
    public void removeMemberRoleListener(ChatRoomMemberRoleListener listener)
    {
        memberRoleListeners.remove(listener);
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

    }

    @Override
    public void removeMemberPropertyChangeListener(
        ChatRoomMemberPropertyChangeListener listener)
    {

    }

    @Override
    public void invite(String userAddress, String reason)
    {

    }

    @Override
    public List<ChatRoomMember> getMembers()
    {
        return members;
    }

    @Override
    public int getMembersCount()
    {
        return members.size();
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
        return protocolProvider;
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

    }

    @Override
    public void grantMembership(String address)
    {

    }

    @Override
    public void grantModerator(String address)
    {
        try
        {
            grantRole(
                    JidCreate.entityFullFrom(address),
                    ChatRoomMemberRole.MODERATOR);
        }
        catch (XmppStringprepException e)
        {
            logger.error("Invalid address to grant moderator", e);
        }
    }

    private void grantRole(EntityFullJid address, ChatRoomMemberRole newRole)
    {
        MockRoomMember member = findMember(address.getResourceOrNull());
        if (member == null)
        {
            logger.error("Member not found for nickname: " + address);
            return;
        }

        ChatRoomMemberRole oldRole = member.getRole();

        member.setRole(newRole);

        fireMemberRoleEvent(member, oldRole);
    }

    private MockRoomMember findMember(Resourcepart nickname)
    {
        if (nickname == null)
        {
            return null;
        }

        for (ChatRoomMember member : members)
        {
            if (nickname.toString().equals(member.getName()))
                return (MockRoomMember) member;
        }
        return null;
    }

    @Override
    public void grantOwnership(String address)
    {
        try
        {
            grantRole(
                    JidCreate.entityFullFrom(address),
                    ChatRoomMemberRole.OWNER);
        }
        catch (XmppStringprepException e)
        {
            logger.error("Invalid address to grant ownership", e);
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

    /**
     * Creates the corresponding ChatRoomMemberPresenceChangeEvent and notifies
     * all <tt>ChatRoomMemberPresenceListener</tt>s that a ChatRoomMember has
     * joined or left this <tt>ChatRoom</tt>.
     *
     * @param member the <tt>ChatRoomMember</tt> that changed its presence
     * status
     * @param actor the <tt>ChatRoomMember</tt> that participated as an actor
     * in this event
     * @param eventID the identifier of the event
     * @param eventReason the reason of this event
     */
    private void fireMemberPresenceEvent(ChatRoomMember member,
                                         ChatRoomMember actor,
                                         String eventID, String eventReason)
    {
        ChatRoomMemberPresenceChangeEvent evt
            = new ChatRoomMemberPresenceChangeEvent(
                    this, member, actor, eventID, eventReason);

        Iterable<ChatRoomMemberPresenceListener> listeners;
        synchronized (memberListeners)
        {
            listeners = new ArrayList<>(memberListeners);
        }

        for (ChatRoomMemberPresenceListener listener : listeners)
            listener.memberPresenceChanged(evt);
    }

    private void fireLocalUserRoleEvent(ChatRoomMember member,
                                        ChatRoomMemberRole oldRole,
                                        boolean isInitial)
    {
        ChatRoomLocalUserRoleChangeEvent evt
            = new ChatRoomLocalUserRoleChangeEvent(
                    this, oldRole, member.getRole(), isInitial);

        Iterable<ChatRoomLocalUserRoleListener> listeners;
        synchronized (localUserRoleListeners)
        {
            listeners = new ArrayList<>(localUserRoleListeners);
        }

        for (ChatRoomLocalUserRoleListener listener : listeners)
            listener.localUserRoleChanged(evt);
    }

    private void fireMemberRoleEvent(ChatRoomMember member,
                                     ChatRoomMemberRole oldRole)
    {
        ChatRoomMemberRoleChangeEvent evt
            = new ChatRoomMemberRoleChangeEvent(
                    this, member, oldRole, member.getRole());

        Iterable<ChatRoomMemberRoleListener> listeners;
        synchronized (memberRoleListeners)
        {
            listeners = new ArrayList<>(memberRoleListeners);
        }

        for (ChatRoomMemberRoleListener listener : listeners)
            listener.memberRoleChanged(evt);
    }

    @Override
    public String toString()
    {
        return "MockMUC@" + hashCode()
            + "["+ this.roomName + ", "
            + protocolProvider + "]";
    }

    @Override
    public XmppChatMember findChatMember(Jid mucJid)
    {
        return findMember(mucJid.getResourceOrNull());
    }
}
