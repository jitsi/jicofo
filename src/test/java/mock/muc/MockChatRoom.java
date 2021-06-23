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

import kotlin.*;
import org.jetbrains.annotations.*;
import org.jitsi.impl.protocol.xmpp.*;

import org.jitsi.jicofo.xmpp.muc.*;
import org.jitsi.utils.*;
import org.jitsi.utils.event.*;
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

    private final EventEmitter<ChatRoomListener> eventEmitter= new SyncEventEmitter<>();

    private final List<ChatRoomMember> members = new CopyOnWriteArrayList<>();

    public MockChatRoom(EntityBareJid roomName, XmppProvider xmppProvider)
    {
        this.roomName = roomName;
        this.xmppProvider = xmppProvider;
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
    public void addListener(@NotNull ChatRoomListener listener)
    {
        eventEmitter.addHandler(listener);
    }

    @Override
    public void removeListener(@NotNull ChatRoomListener listener)
    {
        eventEmitter.addHandler(listener);
    }

    @Override
    public void setPresenceExtension(ExtensionElement extension, boolean remove)
    {
    }

    @Override
    public String getMeetingId()
    {
        return null;
    }

    @Override
    public boolean isAvModerationEnabled(MediaType mediaType)
    {
        return false;
    }

    @Override
    public void setAvModerationEnabled(MediaType mediaType, boolean value)
    {}

    @Override
    public void updateAvModerationWhitelists(Map<String, List<String>> l)
    {}

    @Override
    public boolean isMemberAllowedToUnmute(Jid jid, MediaType mediaType)
    {
        return true;
    }

    @Override
    public EntityBareJid getRoomJid()
    {
        return roomName;
    }

    private EntityFullJid createAddressForName(String nickname)
            throws XmppStringprepException
    {
        return JidCreate.entityFullFrom(roomName, Resourcepart.from(nickname));
    }

    @Override
    public void join()
            throws SmackException
    {
        if (isJoined)
        {
            throw new MultiUserChatException.MucAlreadyJoinedException();
        }

        isJoined = true;
    }

    public MockRoomMember createMockRoomMember(String nickname)
            throws XmppStringprepException
    {
        return new MockRoomMember(createAddressForName(nickname), this);
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
                throw new IllegalArgumentException("The member name " + member.getName() + " is invalid");
            }

            if (findMember(name) != null)
            {
                throw new IllegalArgumentException("The member with name: " + name + " is in the room already");
            }

            members.add(member);
            eventEmitter.fireEvent(handler -> {
                handler.memberJoined(member);
                return Unit.INSTANCE;
            });
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

            eventEmitter.fireEvent(handler -> {
                handler.memberLeft(member);
                return Unit.INSTANCE;
            });
        }
    }

    @Override
    public boolean isJoined()
    {
        return isJoined;
    }

    @Override
    public void setEventExecutor(@NotNull Executor executor)
    {
    }

    @Override
    public void leave()
    {
        if (!isJoined)
            return;

        isJoined = false;
    }

    @Override
    public MemberRole getUserRole()
    {
        return MemberRole.OWNER;
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
