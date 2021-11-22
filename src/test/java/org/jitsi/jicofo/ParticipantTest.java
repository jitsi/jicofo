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

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import mock.muc.*;
import org.jitsi.jicofo.conference.*;
import org.jitsi.utils.logging2.*;
import org.junit.jupiter.api.*;
import org.jxmpp.jid.impl.*;
import org.jxmpp.stringprep.*;

public class ParticipantTest
{
    @Test
    public void testRestartReqRateLimiting()
            throws XmppStringprepException
    {
        Participant p = new Participant(
                new MockRoomMember(JidCreate.entityFullFrom("something@server.com/1234"), null),
                new LoggerImpl(getClass().getName()),
                null);

        p.setClock(Clock.fixed(Instant.now(), ZoneId.systemDefault()));

        assertTrue(p.incrementAndCheckRestartRequests(),
            "should allow 1st request");
        assertFalse(p.incrementAndCheckRestartRequests(),
            "should not allow next request immediately");

        p.setClock(Clock.offset(p.getClock(), Duration.ofSeconds(5)));
        assertFalse(p.incrementAndCheckRestartRequests(),
            "should not allow next request after 5 seconds");

        p.setClock(Clock.offset(p.getClock(), Duration.ofSeconds(6)));
        assertTrue(p.incrementAndCheckRestartRequests(),
            "should allow 2nd request after 11 seconds");

        assertFalse(p.incrementAndCheckRestartRequests(),
            "should not allow 3rd request after 11 seconds");

        p.setClock(Clock.offset(p.getClock(), Duration.ofSeconds(10)));
        assertTrue(p.incrementAndCheckRestartRequests(),
            "should allow 3rd request after 21 seconds");

        p.setClock(Clock.offset(p.getClock(), Duration.ofSeconds(11)));
        assertFalse(p.incrementAndCheckRestartRequests(),
            "should not allow more than 3 request within the last minute (31 second)");
        p.setClock(Clock.offset(p.getClock(), Duration.ofSeconds(10)));
        assertFalse(p.incrementAndCheckRestartRequests(),
            "should not allow more than 3 request within the last minute (41 second)");
        p.setClock(Clock.offset(p.getClock(), Duration.ofSeconds(10)));
        assertFalse(p.incrementAndCheckRestartRequests(),
            "should not allow more than 3 request within the last minute (51 second)");

        p.setClock(Clock.offset(p.getClock(), Duration.ofSeconds(10)));
        assertTrue(p.incrementAndCheckRestartRequests(),
            "should allow the 4th request after 60 seconds have passed since the 1st (61 second)");

        p.setClock(Clock.offset(p.getClock(), Duration.ofSeconds(5)));
        assertFalse(p.incrementAndCheckRestartRequests(),
            "should not allow the 5th request in 66th second");

        p.setClock(Clock.offset(p.getClock(), Duration.ofSeconds(5)));
        assertTrue(p.incrementAndCheckRestartRequests(),
            "should allow the 5th request in 71st second");
    }
}
