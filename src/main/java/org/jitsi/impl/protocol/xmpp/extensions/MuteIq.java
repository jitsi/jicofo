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

import org.jivesoftware.smack.packet.*;
import org.jxmpp.jid.*;

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
    private Jid jid;

    /**
     * To mute or unmute.
     */
    private Boolean mute;

    /**
     * Creates a new instance of this class.
     */
    public MuteIq()
    {
        super(ELEMENT_NAME, NAMESPACE);
    }

    @Override
    protected IQChildElementXmlStringBuilder getIQChildElementBuilder(
            IQChildElementXmlStringBuilder xml)
    {
        xml.attribute(JID_ATTR_NAME, jid)
                .rightAngleBracket()
                .append(mute.toString());
        return xml;
    }

    /**
     * Sets the MUC jid of the user to be muted/unmuted.
     * @param jid muc jid in the form of room_name@muc.server.net/nickname.
     */
    public void setJid(Jid jid)
    {
        this.jid = jid;
    }

    /**
     * Returns MUC jid of the participant in the form of
     * "room_name@muc.server.net/nickname".
     */
    public Jid getJid()
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
