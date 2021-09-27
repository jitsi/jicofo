/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2016-Present 8x8, Inc.
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

import org.jetbrains.annotations.*;
import org.jitsi.impl.protocol.xmpp.*;
import org.jitsi.jicofo.bridge.*;
import org.jitsi.jicofo.conference.*;
import org.jitsi.jicofo.xmpp.*;
import org.jitsi.jicofo.xmpp.muc.*;
import org.jitsi.utils.*;
import org.jitsi.xmpp.extensions.jibri.*;
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
    public Participant findParticipantForRoomJid(Jid jid)
    {
        return null;
    }

    @Override
    public Map<Bridge, Integer> getBridges()
    {
        return new HashMap<>();
    }

    @Override
    public EntityBareJid getRoomName()
    {
        return null;
    }

    @Override
    public ChatRoom getChatRoom()
    {
        return null;
    }

    @Override
    public MemberRole getRoleForMucJid(Jid jid)
    {
        return null;
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

    @Override
    @NotNull
    public IqProcessingResult handleJibriRequest(@NotNull IqRequest<JibriIq> request)
    {
        return new IqProcessingResult.NotProcessed();
    }

    @Override
    public boolean acceptJigasiRequest(@NotNull Jid from)
    {
        return MemberRoleKt.hasModeratorRights(getRoleForMucJid(from));
    }

    @Override
    public @NotNull MuteResult
    handleMuteRequest(Jid muterJid, Jid toBeMutedJid, boolean doMute, MediaType mediaType)
    {
        return MuteResult.SUCCESS;
    }

    @Override
    public void muteAllParticipants(MediaType mediaType)
    {}
}
