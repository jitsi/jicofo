/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.jicofo.log;

import org.jitsi.videobridge.eventadmin.*;
import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;

import java.io.*;
import java.util.*;

import static org.junit.Assert.assertEquals;

/**
 * Just a playground
 *
 * @author Pawel Domas
 */
@RunWith(JUnit4.class)
public class AuthEventsTest
{
    @Test
    public void testAuthSessionCreated()
            throws UnsupportedEncodingException
    {
        String sessionID = "dsf23r23efsDgGBV%2312432@#$";
        String machineUID = "fdsg8973tj!@#gfdg345";
        String userId = "user@server.com";

        Map<String, String> properties = new HashMap<String, String>();

        String propA = "some-property";
        String valueA = "urn:something:idp:com:en";
        properties.put(propA, valueA);

        String propB = "cookie";
        String valueB = "_saml_xml_=fd!sadF45FE; " +
                "_saml_sp=aGrt67DFgfdg; " +
                "something=dgffdg43543534";
        properties.put(propB, valueB);

        Event event = EventFactory.authSessionCreated(
            sessionID, userId, machineUID, properties);

        assertEquals(EventFactory.AUTH_SESSION_CREATED_TOPIC, event.getTopic());

        assertEquals(sessionID, event.getProperty(
                LoggingHandler.AUTHENTICATION_SESSION_COLUMNS[0]));

        assertEquals(userId, event.getProperty(
                LoggingHandler.AUTHENTICATION_SESSION_COLUMNS[1]));

        assertEquals(machineUID, event.getProperty(
                LoggingHandler.AUTHENTICATION_SESSION_COLUMNS[2]));

        String propertiesMerged = (String) event.getProperty(
                LoggingHandler.AUTHENTICATION_SESSION_COLUMNS[3]);

        Map<String, String> propertiesSplit =
            EventFactory.splitProperties(propertiesMerged);

        assertEquals(valueA, propertiesSplit.get(propA));
        assertEquals(valueB, propertiesSplit.get(propB));
    }
}
