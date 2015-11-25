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
package org.jitsi.jicofo;

import org.jivesoftware.smackx.*;
import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;
import static org.junit.Assert.*;

import java.util.*;


/**
 * Tests the form generation for rooms exposing/hiding
 * 
 * @author Maksym Kulish
 */
@RunWith(JUnit4.class)
public class RoomExposingTest 
{
    
    @Before
    public void setUp()
    {
        System.clearProperty(ChatRoomConfigurationFactory.ROOM_EXPOSE_PNAME);
    }
    
    /**
     * Test that configuration form produced by 
     * <tt>ChatRoomConfigurationFactory</tt> contains muc#roomconfig_publicroom
     * variable set to false
     * 
     * @throws Exception
     */
    @Test
    public void testRoomNotExposed() throws Exception
    {
        ChatRoomConfigurationFactory.evaluateRoomExpose();
        ChatRoomConfigurationFactory configurationFactory
            = new ChatRoomConfigurationFactory("anyone");

        Form configurationFormQuestion = new Form("form");
        
        configurationFactory
            .setConfigurationFormQuestion(configurationFormQuestion);
        
        Form configurationForm = configurationFactory
            .getConfigurationFormAnswer();
        
        FormField mucConfigRoomPublicityField
            = configurationForm
            .getField(ChatRoomConfigurationFactory.ROOM_PUBLIC_FIELD_NAME);
        
        assertNotNull(mucConfigRoomPublicityField);
        
        Iterator<String> mucConfigRoomPublicityFieldValuesIterator 
            = mucConfigRoomPublicityField.getValues();
        
        List<String> mucConfigRoomPublicityFieldValues 
            = new ArrayList<>();
        
        while (mucConfigRoomPublicityFieldValuesIterator.hasNext())
        {
            mucConfigRoomPublicityFieldValues
                .add(mucConfigRoomPublicityFieldValuesIterator.next());
        }
        
        assertEquals(1, mucConfigRoomPublicityFieldValues.size());
        assertEquals(
            ChatRoomConfigurationFactory.ROOM_NON_PUBLIC,
            mucConfigRoomPublicityFieldValues.get(0)
        );
    }

    /**
     * Test that configuration form produced by 
     * <tt>ChatRoomConfigurationFactory</tt> does not contain 
     * muc#roomconfig_publicroom variable
     *
     * @throws Exception
     */
    @Test
    public void testRoomExposed() throws Exception
    {
        // Set the system property indicating the rooms should be exposed
        System.setProperty(
            ChatRoomConfigurationFactory.ROOM_EXPOSE_PNAME,
            ChatRoomConfigurationFactory.EXPOSE_ROOMS_VALUE_INDICATOR
        );
        ChatRoomConfigurationFactory.evaluateRoomExpose();
        ChatRoomConfigurationFactory configurationFactory
            = new ChatRoomConfigurationFactory("anyone");

        Form configurationFormQuestion = new Form("form");

        configurationFactory
            .setConfigurationFormQuestion(configurationFormQuestion);

        Form configurationForm = configurationFactory
            .getConfigurationFormAnswer();
        
        FormField mucConfigRoomPublicityField 
            = configurationForm
            .getField(ChatRoomConfigurationFactory.ROOM_PUBLIC_FIELD_NAME);

        assertNull(mucConfigRoomPublicityField);
    }
}
