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
package org.jitsi.jicofo.recording.jibri;

import org.jitsi.eventadmin.*;
import org.jitsi.utils.logging.*;
import org.jxmpp.jid.*;
import org.jxmpp.jid.impl.*;
import org.jxmpp.stringprep.*;

import java.util.*;

/**
 * The events used to notify about the availability and busy/idle status
 * updates of Jibri instances which exist in the current Jicofo session.
 *
 * @author Pawel Domas
 */
public class JibriEvent
    extends Event
{
    /** Class logger */
    private static final Logger logger = Logger.getLogger(JibriEvent.class);

    /**
     * The event is triggered by {@link JibriDetector} whenever new Jibri is
     * available or when some of the existing Jibri changes the status between
     * idle and busy.
     */
    public static final String STATUS_CHANGED = "org/jitsi/jicofo/JIBRI/STATUS";

    /**
     * The event is triggered by {@link JibriDetector} whenever a Jibri goes
     * down(stops working or disconnects).
     */
    public static final String WENT_OFFLINE = "org/jitsi/jicofo/JIBRI/OFFLINE";

    /**
     * The key for event property which stored the JID of a Jibri(XMPP address).
     */
    private final static String JIBRI_JID_KEY = "jibri.status";

    /**
     * The key for event property which stores a <tt>boolean</tt> holding
     * Jibri's idle status.
     */
    private final static String IS_IDLE_KEY = "jibri.is_idle";

    /**
     * The key for event property which stores a <tt>boolean</tt> telling
     * whether or not the Jibri instance associated with the event is a video
     * SIP gateway Jibri (<tt>true</tt>) or a live streaming Jibri
     * (<tt>false</tt>).
     */
    private final static String IS_SIP_KEY = "jibri.is_sip";

    /**
     * Used to init the properties passed to the constructor.
     * @param jibriJid the Jibri JID(XMPP address)
     * @param isIdle a <tt>Boolean</tt> with Jibri's idle status or
     *        <tt>null</tt> if it should not be included.
     * @param isSIP <tt>true</tt> for video SIP gateway Jibri or <tt>false</tt>
     *        for live streaming type of Jibri.
     */
    static private Dictionary<String, Object> initDictionary(
        Jid jibriJid,
        Boolean isIdle,
        boolean isSIP)
    {
        Dictionary<String, Object> props = new Hashtable<>();
        props.put(JIBRI_JID_KEY, jibriJid);
        props.put(IS_SIP_KEY, isSIP);
        if (isIdle != null)
        {
            props.put(IS_IDLE_KEY, isIdle);
        }
        return props;
    }

    /**
     * Creates {@link #STATUS_CHANGED} <tt>JibriEvent</tt>.
     *
     * @param jibriJid the JID of the Jibri for which the event will be created.
     * @param isIdle a boolean indicating whether the current Jibri's status is
     * idle (<tt>true</tt>) or busy (<tt>false</tt>).
     * @param isSIP <tt>true</tt> if it's a video SIP gateway Jibri or
     * <tt>false</tt> for the live streaming type of Jibri.
     *
     * @return {@link #STATUS_CHANGED} <tt>JibriEvent</tt> for given
     * <tt>jibriJid</tt>.
     */
    static public JibriEvent newStatusChangedEvent(
        Jid jibriJid,
        boolean isIdle,
        boolean isSIP)
    {
        return new JibriEvent(STATUS_CHANGED, jibriJid, isIdle, isSIP);
    }

    /**
     * Creates {@link #WENT_OFFLINE} <tt>JibriEvent</tt>.
     *
     * @param jibriJid the JID of the Jibri for which the event will be created.
     * @param isSIP <tt>true</tt> if it's a video SIP gateway Jibri or
     * <tt>false</tt> for the live streaming type of Jibri.
     *
     * @return {@link #WENT_OFFLINE} <tt>JibriEvent</tt> for given
     * <tt>jibriJid</tt>.
     */
    static public JibriEvent newWentOfflineEvent(
        Jid jibriJid, boolean isSIP)
    {
        return new JibriEvent(WENT_OFFLINE, jibriJid, null, isSIP);
    }

    /**
     * Checks whether or not given <tt>Event</tt> is a <tt>BridgeEvent</tt>.
     *
     * @param event the <tt>Event</tt> instance to be checked.
     *
     * @return <tt>true</tt> if given <tt>Event</tt> instance is one of bridge
     *         events or <tt>false</tt> otherwise.
     */
    static public boolean isJibriEvent(Event event)
    {
        switch (event.getTopic())
        {
            case STATUS_CHANGED:
            case WENT_OFFLINE:
                return true;
            default:
                return false;
        }
    }

    private JibriEvent(String topic,
        Jid jibriJid, Boolean isIdle, boolean isSIP)
    {
        super(topic, initDictionary(jibriJid, isIdle, isSIP));
    }

    /**
     * Gets Jibri JID associated with this <tt>JibriEvent</tt> instance.
     *
     * @return <tt>String</tt> which is a JID of the Jibri for which this event
     *         instance has been created.
     */
    public Jid getJibriJid()
    {
        try
        {
            return JidCreate.from(getProperty(JIBRI_JID_KEY).toString());
        }
        catch (XmppStringprepException e)
        {
            logger.error("Invalid Jibri JID", e);
            return null;
        }
    }

    /**
     * Tells whether or not the current status of the Jibri associated with this
     * event is currently idle (<tt>true</tt>) or busy (<tt>false</tt>).
     *
     * @return a <tt>boolean</tt> value of <tt>true</tt> for idle or
     *         <tt>false</tt> for busy.
     *
     * @throws IllegalStateException if the method is called on
     *         a <tt>JibriEvent</tt> which does not support this property.
     */
    public boolean isIdle()
    {
        Boolean isIdle = (Boolean) getProperty(IS_IDLE_KEY);
        if (isIdle == null)
        {
            throw new IllegalStateException(
                "Trying to access 'isIdle' on wrong event type: " + getTopic());
        }
        return isIdle;
    }

    /**
     * @return <tt>true</tt> if the event is for SIP Jibri or <tt>false</tt>
     * for a regular Jibri.
     */
    public boolean isSIP()
    {
        Boolean isSIP = (Boolean) this.getProperty(IS_SIP_KEY);
        return isSIP != null ? isSIP : false;
    }
}
