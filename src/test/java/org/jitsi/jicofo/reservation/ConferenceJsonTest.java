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
package org.jitsi.jicofo.reservation;

import org.jitsi.impl.reservation.rest.*;
import org.jitsi.impl.reservation.rest.json.*;
import org.json.simple.parser.*;
import org.junit.*;
import org.jxmpp.jid.impl.*;
import org.jxmpp.stringprep.*;

import java.io.*;
import java.util.*;

import static org.junit.Assert.*;

/**
 * Tests/playground for {@link ConferenceJsonHandler}.
 *
 * @author Pawel Domas
 */
public class ConferenceJsonTest
{
    @Test
    public void testParseConference()
        throws Exception
    {
        // ConferenceIq
        String confJson =
            "{\n" +
                    "\"id\": 1234,\n" +
                    "\"url\": \"http://conference.net/1234\",\n" +
                    "\"pin\": \"1231\",\n" +
                    "\"sip_id\": 23,\n" +
                    "\"start_time\": \"2015-02-23T09:03:34.000Z\"" +
            "}";

        JSONParser parser = new JSONParser();
        ConferenceJsonHandler handler = new ConferenceJsonHandler();

        parser.parse(new StringReader(confJson), handler);

        Conference conference = handler.getResult();

        assertNotNull(conference);
        assertEquals(1234, conference.getId().intValue());
        assertEquals("http://conference.net/1234", conference.getUrl());
        assertEquals("1231", conference.getPin());
        assertEquals(23L, conference.getSipId());
    }

    @Test
    public void testParseError()
            throws Exception
    {
        // Error JSon
        String errorJson =
                "{\n" +
                    "\"error\": \"404\"\n" +
                    "\"message\": \"not found\"\n" +
                "}";

        JSONParser parser = new JSONParser();
        ErrorJsonHandler handler = new ErrorJsonHandler();

        parser.parse(new StringReader(errorJson), handler);

        ErrorResponse error = handler.getResult();

        assertNotNull(error);
        assertEquals("404", error.getError());
        assertEquals("not found", error.getMessage());
    }

    @Test
    public void testToJson()
            throws XmppStringprepException
    {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.YEAR, 2014);
        c.set(Calendar.MONTH, Calendar.JANUARY);
        c.set(Calendar.DAY_OF_MONTH, 8);
        c.set(Calendar.HOUR_OF_DAY, 9);
        c.set(Calendar.MINUTE, 2);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);

        Conference conf = new Conference(
                JidCreate.entityBareFrom("test1@example.com"),
                "pawel.gawel",
                c.getTime());

        Map<String, Object> objects = conf.createJSonMap();

        assertEquals("test1" ,objects.get(ConferenceJsonHandler.CONF_NAME_KEY));
        assertEquals("pawel.gawel", objects.get(ConferenceJsonHandler
                .CONF_OWNER_KEY));

        // FIXME: This will fail in different time zone
        //assertEquals(
        //    "2014-01-08T09:02:00.000+01",
        //    objects.get(ConferenceJsonHandler.CONF_START_TIME_KEY));

        //assertEquals("00:05" ,objects.get(ConferenceJsonHandler
          //  .CONF_DURATION_KEY));
    }
}
