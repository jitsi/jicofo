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
package org.jitsi.impl.protocol.xmpp.extensions;

import org.jitsi.util.*;

import org.jivesoftware.smack.packet.*;

/**
 * IQ used for the signaling of audio muting functionality in Jitsi Meet
 * conferences.
 *
 * @author Pawel Domas
 */
public class MuteIq
    extends IQ
{
    /**
     * Name space of mute packet extension.
     */
    public static final String NAMESPACE = "http://jitsi.org/jitmeet/audio";

    /**
     * XML element name of mute packet extension.
     */
    public static final String ELEMENT_NAME = "mute";

    /**
     * Attribute name of "jid".
     */
    public static final String JID_ATTR_NAME = "jid";

    /**
     * Muted peer MUC jid.
     */
    private String jid;

    /**
     * To mute or unmute.
     */
    private Boolean mute;

    @Override
    public String getChildElementXML()
    {
        StringBuilder output = new StringBuilder();

        output.append("<mute ")
            .append("xmlns='").append(NAMESPACE).append("' ");
        if (!StringUtils.isNullOrEmpty(jid))
        {
            output.append("jid='").append(jid).append("'");
        }
        if (mute != null)
        {
            output.append(">").append(mute).append("</mute>");
        }
        else
        {
            output.append("/>");
        }
        return output.toString();
    }

    /**
     * Sets the MUC jid of the user to be muted/unmuted.
     * @param jid muc jid in the form of room_name@muc.server.net/nickname.
     */
    public void setJid(String jid)
    {
        this.jid = jid;
    }

    /**
     * Returns MUC jid of the participant in the form of
     * "room_name@muc.server.net/nickname".
     */
    public String getJid()
    {
        return jid;
    }

    /**
     * The action contained in the text part of 'mute' XML element body.
     * @param mute <tt>true</tt> to mute the participant. <tt>null</tt> means no
     *             action is included in result XML.
     */
    public void setMute(Boolean mute)
    {
        this.mute = mute;
    }

    /**
     * Returns <tt>true</tt> to mute the participant, <tt>false</tt> to unmute
     * or <tt>null</tt> if the action has not been specified(which is invalid).
     */
    public Boolean getMute()
    {
        return mute;
    }
}
