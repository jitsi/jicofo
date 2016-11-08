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
package org.jitsi.jicofo.event;

import org.jitsi.eventadmin.*;
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
                EventFactory.AUTH_SESSION_ID_KEY));

        assertEquals(userId, event.getProperty(
                EventFactory.USER_IDENTITY_KEY));

        assertEquals(machineUID, event.getProperty(
                EventFactory.MACHINE_UID_KEY));

        String propertiesMerged = (String) event.getProperty(
                EventFactory.AUTH_PROPERTIES_KEY);

        Map<String, String> propertiesSplit =
            EventFactory.splitProperties(propertiesMerged);

        assertEquals(valueA, propertiesSplit.get(propA));
        assertEquals(valueB, propertiesSplit.get(propB));
    }
}
