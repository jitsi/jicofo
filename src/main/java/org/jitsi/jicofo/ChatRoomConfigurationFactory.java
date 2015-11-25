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

import org.jitsi.util.*;
import org.jivesoftware.smackx.*;

/**
 * This is the factory for the <tt>Form</tt> that will be used for 
 * MUC configuration
 * 
 * @author Maksym Kulish
 */
public class ChatRoomConfigurationFactory
{
    /**
     * The boolean flag indicating whether this <tt>ChatRoom</tt> is ought
     * to be exposed for disco#items IQs
     */
    private static Boolean isExposed = null;

    /**
     * Configuration property which specifies the string that evaluates to
     * <tt>isExposed</tt> boolean flag (basically it is set to True, when 
     * ""
     */
    public static final String ROOM_EXPOSE_PNAME
            = "org.jitsi.focus.EXPOSE_ROOMS";

    /**
     * The configuration form field name that is ought to be used to set
     * XMPP MUC config whois
     */
    public static final String ROOM_WHOIS_FIELD_NAME = "muc#roomconfig_whois";

    /**
     * The configuration form field name that is ought to be used to set
     * XMPP MUC privacy
     */
    public static final String ROOM_PUBLIC_FIELD_NAME 
        = "muc#roomconfig_publicroom";

    /**
     * The XMPP constant that sets the room non-public
     */
    public static final String ROOM_NON_PUBLIC = "false";
    
    /**
     * Evaluates and sets the configuration bits for room exposing
     *
     */
    public static void evaluateRoomExpose()
    {
        String roomExposeFlag = System.getProperty(
            ROOM_EXPOSE_PNAME, ROOM_NON_PUBLIC
        );
        isExposed = StringUtils.isEquals(
           roomExposeFlag, EXPOSE_ROOMS_VALUE_INDICATOR
        );
    }
    /**
     * The value of the parameter which indicates that rooms managed by this
     * <tt>FocusManager</tt> are to be exposed for disco#items IQs
     */
    public static final String EXPOSE_ROOMS_VALUE_INDICATOR = "true";
    
    /**
     * The room whois configuration value
     */
    private String roomWhois;

    /**
     * The <tt>Form</tt> received as MUC configuration question from XMPP
     * server
     */
    private Form configurationFormQuestion;

    /**
     * The constructor for <tt>ChatRoomConfigurationFactory</tt>
     * 
     * @param roomWhois - the parameter to be used as muc#roomconfig_whois
     *                  in configuration form
     */
    public ChatRoomConfigurationFactory(String roomWhois)
    {
        this.roomWhois = roomWhois;
    }

    /**
     * Set the <tt>Form</tt> that serves as MUC configuration form question
     * 
     * @param configurationFormQuestion the <tt>Form</tt> that serves as 
     *                                  MUC configuration form question
     */
    public void setConfigurationFormQuestion(Form configurationFormQuestion)
    {
        this.configurationFormQuestion = configurationFormQuestion;
    }

    /**
     * Get the <tt>Form</tt> that will be sent to XMPP server to configure
     * MUC controlled by Jicofo
     * 
     * @return <tt>Form</tt> that will be sent to XMPP server to configure
     *         MUC controlled by Jicofo
     */
    public Form getConfigurationFormAnswer() {
        
        // Comments preserved for historical reasons
        //muc.join(nickname);
        
            /*Iterator<FormField> fields = config.getFields();
            while (fields.hasNext())
            {
                FormField field = fields.next();
                logger.info("FORM: " + field.toXML());
            }*/
        Form answer = configurationFormQuestion.createAnswerForm();
        // Room non-anonymous
        FormField whois = new FormField(ROOM_WHOIS_FIELD_NAME);
        whois.addValue(roomWhois);
        answer.addField(whois);

        // Make room non-public, if otherwise is not explicitly stated
        if (!isExposed)
        {
            FormField exposed = new FormField(ROOM_PUBLIC_FIELD_NAME);
            exposed.addValue(ROOM_NON_PUBLIC);
            answer.addField(exposed);
        }

        // Room moderated
        //FormField roomModerated
        //    = new FormField("muc#roomconfig_moderatedroom");
        //roomModerated.addValue("true");
        //answer.addField(roomModerated);
        // Only participants can send private messages
        //FormField onlyParticipantsPm
        //        = new FormField("muc#roomconfig_allowpm");
        //onlyParticipantsPm.addValue("participants");
        //answer.addField(onlyParticipantsPm);
        // Presence broadcast
        //FormField presenceBroadcast
        //        = new FormField("muc#roomconfig_presencebroadcast");
        //presenceBroadcast.addValue("participant");
        //answer.addField(presenceBroadcast);
        // Get member list
        //FormField getMemberList
        //        = new FormField("muc#roomconfig_getmemberlist");
        //getMemberList.addValue("participant");
        //answer.addField(getMemberList);
        // Public logging
        //FormField publicLogging
        //        = new FormField("muc#roomconfig_enablelogging");
        //publicLogging.addValue("false");
        //answer.addField(publicLogging);
        
        return answer;
    }
}
