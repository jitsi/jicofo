/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.jicofo.reservation;

import org.jitsi.impl.reservation.rest.*;
import org.jitsi.impl.reservation.rest.json.*;
import org.json.simple.parser.*;
import org.junit.*;

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
            "test1", "pawel.gawel", c.getTime());

        Map<String, Object> objects = conf.createJSonMap();

        assertEquals("test1" ,objects.get(ConferenceJsonHandler.CONF_NAME_KEY));
        assertEquals("pawel.gawel", objects.get(ConferenceJsonHandler
                .CONF_OWNER_KEY));

        // FIXME: This will fail in different time zone
        assertEquals(
            "2014-01-08T09:02:00.000+01",
            objects.get(ConferenceJsonHandler.CONF_START_TIME_KEY));

        //assertEquals("00:05" ,objects.get(ConferenceJsonHandler
          //  .CONF_DURATION_KEY));
    }
}
