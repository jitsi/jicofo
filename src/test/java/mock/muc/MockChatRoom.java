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
package mock.muc;

import org.jitsi.impl.protocol.xmpp.*;
import org.jitsi.jicofo.*;

import org.jitsi.jicofo.xmpp.muc.*;
import org.jitsi.utils.logging2.*;
import org.jivesoftware.smack.*;
import org.jivesoftware.smack.packet.*;
import org.jivesoftware.smackx.muc.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;
import org.jxmpp.jid.parts.*;
import org.jxmpp.stringprep.*;

import java.lang.*;
import java.lang.String;
import java.util.*;
import java.util.concurrent.*;

import static org.jitsi.impl.protocol.xmpp.ChatRoomMemberPresenceChangeEvent.*;

/**
 * Mock {@link ChatRoom} implementation.
 *
 * @author Pawel Domas
 */
public class MockChatRoom
    implements ChatRoom
{
    /**
     * The logger
     */
    private static final Logger logger = new LoggerImpl(MockChatRoom.class.getName());

    private final EntityBareJid roomName;

    private final XmppProvider xmppProvider;

    private volatile boolean isJoined;

    private final List<ChatRoomMember> members = new CopyOnWriteArrayList<>();

    private ChatRoomMember me;

    /**
     * Listeners that will be notified of changes in member status in the
     * room such as member joined, left or being kicked or dropped.
     */
    private final Vector<ChatRoomMemberPresenceListener> memberListeners = new Vector<>();

    private final Vector<ChatRoomLocalUserRoleListener> localUserRoleListeners = new Vector<>();

    // The nickname to join with
    private final String myNickname;

    public MockChatRoom(EntityBareJid roomName, XmppProvider xmppProvider, String myNickname)
    {
        this.roomName = roomName;
        this.xmppProvider = xmppProvider;
        this.myNickname = myNickname;
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
        //FIXME: to be tested
    }

    @Override
    public void setConference(JitsiMeetConference conference)
    {
    }

    @Override
    public void setPresenceExtension(ExtensionElement extension, boolean remove)
    {
    }

    @Override
    public String getLocalNickname()
    {
        return myNickname;
    }

    @Override
    public String getMeetingId()
    {
        return null;
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
    public void join()
            throws SmackException
    {
        joinAs(myNickname);
    }

    private EntityFullJid createAddressForName(String nickname)
            throws XmppStringprepException
    {
        return JidCreate.entityFullFrom(roomName, Resourcepart.from(nickname));
    }

    private void joinAs(String nickname)
            throws SmackException
    {
        if (isJoined)
            throw new MultiUserChatException.MucAlreadyJoinedException();

        isJoined = true;

        MockRoomMember member;
        try
        {
            member = new MockRoomMember(createAddressForName(nickname), this);
        }
        catch (XmppStringprepException e)
        {
            throw new RuntimeException("Invalid mock room member JID", e);
        }

        // FIXME: for mock purposes we are always the owner on join()
        boolean isOwner = true;//= members.size() == 0;

        synchronized (members)
        {
            members.add(member);
            me = member;
            fireMemberPresenceEvent(new Joined(me));
        }

        if (isOwner)
        {
            me.setRole(MemberRole.OWNER);
        }

        fireLocalUserRoleEvent(me, true);
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
            fireMemberPresenceEvent(new Joined(member));
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
                throw new RuntimeException("Member is not in the room " + member);
            }

            fireMemberPresenceEvent(new Left(member));
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

            fireMemberPresenceEvent(new Left(me));
        }

        me = null;
    }

    @Override
    public MemberRole getUserRole()
    {
        return MemberRole.OWNER;
    }

    @Override
    public void addMemberPresenceListener(ChatRoomMemberPresenceListener listener)
    {
        synchronized (memberListeners)
        {
            memberListeners.add(listener);
        }
    }

    @Override
    public void removeMemberPresenceListener(ChatRoomMemberPresenceListener listener)
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
    public void removeLocalUserRoleListener(ChatRoomLocalUserRoleListener listener)
    {
        localUserRoleListeners.remove(listener);
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

    private void grantRole(EntityFullJid address, MemberRole newRole)
    {
        MockRoomMember member = findMember(address.getResourceOrNull());
        if (member == null)
        {
            logger.error("Member not found for nickname: " + address);
            return;
        }

        member.setRole(newRole);
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
            grantRole(JidCreate.entityFullFrom(address), MemberRole.OWNER);
        }
        catch (XmppStringprepException e)
        {
            logger.error("Invalid address to grant ownership", e);
        }
    }

    @Override
    public boolean destroy(String reason, String alternateAddress)
    {
        return false;
    }

    /**
     * Creates the corresponding ChatRoomMemberPresenceChangeEvent and notifies
     * all <tt>ChatRoomMemberPresenceListener</tt>s that a ChatRoomMember has
     * joined or left this <tt>ChatRoom</tt>.
     */
    private void fireMemberPresenceEvent(ChatRoomMemberPresenceChangeEvent evt)
    {
        Iterable<ChatRoomMemberPresenceListener> listeners;
        synchronized (memberListeners)
        {
            listeners = new ArrayList<>(memberListeners);
        }

        for (ChatRoomMemberPresenceListener listener : listeners)
            listener.memberPresenceChanged(evt);
    }

    private void fireLocalUserRoleEvent(ChatRoomMember member, boolean isInitial)
    {
        ChatRoomLocalUserRoleChangeEvent evt = new ChatRoomLocalUserRoleChangeEvent(member.getRole(), isInitial);

        Iterable<ChatRoomLocalUserRoleListener> listeners;
        synchronized (localUserRoleListeners)
        {
            listeners = new ArrayList<>(localUserRoleListeners);
        }

        for (ChatRoomLocalUserRoleListener listener : listeners)
            listener.localUserRoleChanged(evt);
    }

    @Override
    public String toString()
    {
        return "MockMUC@" + hashCode() + "["+ this.roomName + ", " + xmppProvider + "]";
    }

    @Override
    public ChatRoomMember findChatMember(Jid mucJid)
    {
        return findMember(mucJid.getResourceOrNull());
    }
}
