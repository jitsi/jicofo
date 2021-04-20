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
import org.jitsi.jicofo.xmpp.muc.*;
import org.jivesoftware.smack.packet.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.parts.*;

/**
 * @author Pawel Domas
 */
public class MockRoomMember
    implements ChatRoomMember
{
    private final Resourcepart name;

    private final EntityFullJid address;

    private final MockChatRoom room;

    private MemberRole role = MemberRole.GUEST;

    public MockRoomMember(EntityFullJid address, MockChatRoom chatRoom)
    {
        this.address = address;
        this.name = address.getResourceOrThrow();
        this.room = chatRoom;
    }

    @Override
    public EntityFullJid getOccupantJid()
    {
        return address;
    }

    @Override
    public String getName()
    {
        return name.toString();
    }

    @Override
    public MemberRole getRole()
    {
        return role;
    }

    public void leave()
    {
        room.mockLeave(getName());
    }

    @Override
    public void setRole(MemberRole role)
    {
        this.role = role;
    }

    @Override
    public String toString()
    {
        return "Member@" + hashCode() + "[" + address +"]";
    }

    @Override
    public Jid getJid()
    {
        return address;
    }

    @Override
    public int getJoinOrderNumber()
    {
        //FIXME: implement in order to test start muted feature
        return 0;
    }

    @Override
    public boolean isRobot()
    {
        return false;
    }

    @Override
    public Presence getPresence()
    {
        // FIXME: not implemented
        return null;
    }

    @Override
    public String getRegion()
    {
        return null;
    }

    @Override
    public String getStatsId()
    {
        return null;
    }
}
