/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2016 Atlassian Pty Ltd
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
package org.jitsi.jicofo;

import net.java.sip.communicator.service.protocol.*;
import org.jitsi.jicofo.bridge.*;
import org.jitsi.protocol.xmpp.*;
import org.jitsi.utils.logging.*;
import org.jxmpp.jid.*;

import java.util.*;

/**
 * Mock {@link JitsiMeetConference} implementation.
 *
 * @author Pawel Domas
 */
public class MockJitsiMeetConference
    implements JitsiMeetConference
{
    /**
     * {@inheritDoc}
     */
    @Override
    public Logger getLogger()
    {
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Participant findParticipantForRoomJid(Jid jid)
    {
        return null;
    }

    @Override
    public List<Bridge> getBridges()
    {
        return new LinkedList<>();
    }

    @Override
    public EntityBareJid getRoomName()
    {
        return null;
    }

    @Override
    public EntityFullJid getFocusJid()
    {
        return null;
    }

    @Override
    public ChatRoom2 getChatRoom()
    {
        return null;
    }

    @Override
    public void setStartMuted(boolean[] startMuted)
    {
    }

    @Override
    public ChatRoomMemberRole getRoleForMucJid(Jid jid)
    {
        return null;
    }

    @Override
    public boolean isFocusMember(Jid jid)
    {
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getParticipantCount()
    {
        return 0;
    }

    @Override
    public boolean includeInStatistics()
    {
        return true;
    }
}
