/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2018 - present 8x8, Inc.
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

import mock.muc.*;
import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;
import org.jxmpp.jid.impl.*;
import org.jxmpp.stringprep.*;

import java.time.*;

import static org.junit.Assert.*;

@RunWith(JUnit4.class)
public class ParticipantTest
{
    @Test
    public void testRestartReqRateLimiting()
            throws XmppStringprepException
    {
        Participant p = new Participant(
                new MockJitsiMeetConference(),
                new MockRoomMember(JidCreate.entityFullFrom("something@server.com/1234"), null),
                0);

        p.setClock(Clock.fixed(Instant.now(), ZoneId.systemDefault()));

        assertTrue("should allow 1st request", p.incrementAndCheckRestartRequests());
        assertFalse("should not allow next request immediately", p.incrementAndCheckRestartRequests());

        p.setClock(Clock.offset(p.getClock(), Duration.ofSeconds(5)));
        assertFalse("should not allow next request after 5 seconds", p.incrementAndCheckRestartRequests());

        p.setClock(Clock.offset(p.getClock(), Duration.ofSeconds(6)));
        assertTrue("should allow 2nd request after 11 seconds", p.incrementAndCheckRestartRequests());

        assertFalse("should not allow 3rd request after 11 seconds", p.incrementAndCheckRestartRequests());

        p.setClock(Clock.offset(p.getClock(), Duration.ofSeconds(10)));
        assertTrue("should allow 3rd request after 21 seconds", p.incrementAndCheckRestartRequests());

        p.setClock(Clock.offset(p.getClock(), Duration.ofSeconds(11)));
        assertFalse("should not allow more than 3 request within the last minute (31 second)", p.incrementAndCheckRestartRequests());
        p.setClock(Clock.offset(p.getClock(), Duration.ofSeconds(10)));
        assertFalse("should not allow more than 3 request within the last minute (41 second)", p.incrementAndCheckRestartRequests());
        p.setClock(Clock.offset(p.getClock(), Duration.ofSeconds(10)));
        assertFalse("should not allow more than 3 request within the last minute (51 second)", p.incrementAndCheckRestartRequests());

        p.setClock(Clock.offset(p.getClock(), Duration.ofSeconds(10)));
        assertTrue("should allow the 4th request after 60 seconds have passed since the 1st (61 second)", p.incrementAndCheckRestartRequests());

        p.setClock(Clock.offset(p.getClock(), Duration.ofSeconds(5)));
        assertFalse("should not allow the 5th request in 66th second", p.incrementAndCheckRestartRequests());

        p.setClock(Clock.offset(p.getClock(), Duration.ofSeconds(5)));
        assertTrue("should allow the 5th request in 71st second", p.incrementAndCheckRestartRequests());
    }
}
