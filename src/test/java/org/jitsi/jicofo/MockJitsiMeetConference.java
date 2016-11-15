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

import org.jitsi.protocol.xmpp.colibri.*;
import org.jitsi.util.*;

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
    public Participant findParticipantForRoomJid(String jid)
    {
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ColibriConference getColibriConference()
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

}
