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
import org.jxmpp.jid.*;

import java.util.*;

import static org.apache.commons.lang3.StringUtils.*;

/**
 * A utility class with static methods which initialize <tt>Event</tt> instances
 * with pre-determined fields.
 *
 * @author Boris Grozev
 * @author Pawel Domas
 */
public class EventFactory
    extends AbstractEventFactory
{
    /**
     * The name of the key for additional authentication properties.
     */
    public static final String AUTH_PROPERTIES_KEY = "properties";

    /**
     * The name of the key for authentication session ID.
     */
    public static final String AUTH_SESSION_ID_KEY = "auth_session_id";

    /**
     * The name of the key for videobridge JID.
     */
    public static final String BRIDGE_JID_KEY = "bridge_jid";

    /**
     * The name of the key for conference ID.
     */
    public static final String CONFERENCE_ID_KEY = "conference_id";

    /**
     * The name of the key for display name.
     */
    public static final String DISPLAY_NAME_KEY = "display_name";

    /**
     * The name of the key for endpoint ID.
     */
    public static final String ENDPOINT_ID_KEY = "endpoint_id";

    /**
     * The name of the key for focus instance ID.
     */
    public static final String FOCUS_ID_KEY = "focus_id";

    /**
     * The name of the key for machine unique identifier supplied during
     * authentication.
     */
    public static final String MACHINE_UID_KEY = "machine_uid";

    /**
     * The name of the key for MUC room JID.
     */
    public static final String ROOM_JID_KEY = "room_jid";

    /**
     * The name of the key for authenticated user identity.
     */
    public static final String USER_IDENTITY_KEY = "user_identity";

    /**
     * The name of the topic of a "conference room" event.
     */
    public static final String CONFERENCE_ROOM_TOPIC
        = "org/jitsi/jicofo/CONFERENCE_ROOM_CREATED";

    /**
     * The name of the topic of an "authentication session created" event.
     */
    public static final String AUTH_SESSION_CREATED_TOPIC
        = "org/jitsi/jicofo/AUTH_SESSION_CREATED";

    /**
     * The name of the topic of an "authentication session destroyed" event.
     */
    public static final String AUTH_SESSION_DESTROYED_TOPIC
        = "org/jitsi/jicofo/AUTH_SESSION_DESTROYED";

    /**
     * The name of the topic of an "endpoint authenticated" event.
     */
    public static final String ENDPOINT_AUTHENTICATED_TOPIC
        = "org/jitsi/jicofo/ENDPOINT_AUTHENTICATED";

    /**
     * The name of the topic of a "focus instance created" event.
     */
    public static final String FOCUS_CREATED_TOPIC
        = "org/jitsi/jicofo/FOCUS_CREATED";

    /**
     * The name of the topic of a "focus joined MUC room" event which is fired
     * just after Jicofo joins the MUC room.
     */
    public static final String FOCUS_JOINED_ROOM_TOPIC
        = "org/jitsi/jicofo/FOCUS_JOINED_ROOM";

    /**
     * The name of the topic of a "focus instance destroyed" event.
     */
    public static final String FOCUS_DESTROYED_TOPIC
        = "org/jitsi/jicofo/FOCUS_DESTROYED";

    /**
     * Creates new <tt>Event</tt> for {@link #FOCUS_JOINED_ROOM_TOPIC}.
     *
     * @param roomJid the full address of MUC room.
     * @param focusId focus instance identifier.
     *
     * @return new <tt>Event</tt> for {@link #FOCUS_JOINED_ROOM_TOPIC}.
     */
    public static Event focusJoinedRoom(
            EntityBareJid roomJid,
            String focusId)
    {
        Dictionary<String, Object> props = new Hashtable<>(2);

        props.put(ROOM_JID_KEY, roomJid);
        props.put(FOCUS_ID_KEY, focusId);

        return new Event(FOCUS_JOINED_ROOM_TOPIC, props);
    }

    /**
     * Creates a new "room conference" <tt>Event</tt> which binds a COLIBRI
     * conference ID to the JID of the associated MUC.
     *
     * @param conferenceId the ID of the COLIBRI conference.
     * @param roomJid the JID of the MUC for which the focus was created.
     *
     * @param focus
     * @return the <tt>Event</tt> which was created.
     */
    public static Event conferenceRoom(
            String conferenceId,
            EntityBareJid roomJid,
            String focus,
            Jid bridgeJid)
    {
        Dictionary<String, Object> props = new Hashtable<>(4);

        props.put(CONFERENCE_ID_KEY, conferenceId);
        props.put(ROOM_JID_KEY, roomJid);
        props.put(FOCUS_ID_KEY, focus);
        props.put(BRIDGE_JID_KEY, bridgeJid);

        return new Event(CONFERENCE_ROOM_TOPIC, props);
    }

    /**
     * Creates new "authentication session created" event.
     *
     * @param sessionId authentication session identifier.
     * @param userIdentity authenticated user identity
     * @param machineUid machine unique identifier used to distinguish session
     *                   for the same user on different machines.
     * @param properties the map of additional properties to be logged provided
     *                   during authentication.
     *
     * @return "authentication session created" <tt>Event</tt>
     */
    public static Event authSessionCreated(
            String sessionId,  String              userIdentity,
            String machineUid, Map<String, String> properties )
    {
        Dictionary<String, Object> props = new Hashtable<>(4);

        props.put(AUTH_SESSION_ID_KEY, sessionId);
        props.put(USER_IDENTITY_KEY, userIdentity);
        props.put(MACHINE_UID_KEY, machineUid);
        props.put(AUTH_PROPERTIES_KEY, mergeProperties(properties));

        return new Event(AUTH_SESSION_CREATED_TOPIC, props);
    }

    /**
     * Creates new "authentication session destroyed" event.
     *
     * @param sessionId authentication session identifier string.
     *
     * @return created "authentication session destroyed" <tt>Event</tt>.
     */
    public static Event authSessionDestroyed(String sessionId)
    {
        Dictionary<String, Object> props = new Hashtable<>(1);

        props.put(AUTH_SESSION_ID_KEY, sessionId);

        return new Event(AUTH_SESSION_DESTROYED_TOPIC, props);
    }

    /**
     * Creates "endpoint authenticated" event.
     *
     * @param sessionId authentication session identifier.
     * @param focusId focus instance id which was hosting the conference session
     * @param endpointId the ID of authenticated Colibri endpoint(participant).
     *
     * @return created "endpoint authenticated" <tt>Event</tt>.
     */
    public static Event endpointAuthenticated(String sessionId,
                                              String focusId,
                                              String endpointId)
    {
        Dictionary<String, Object> props = new Hashtable<>(3);

        props.put(AUTH_SESSION_ID_KEY, sessionId);
        props.put(FOCUS_ID_KEY, focusId);
        props.put(ENDPOINT_ID_KEY, endpointId);

        return new Event(ENDPOINT_AUTHENTICATED_TOPIC, props);
    }

    /**
     * Creates new "focus created" event.
     *
     * @param focusId focus instance identifier.
     * @param roomName MUC room JID for which the focus has been created.
     *
     * @return new "focus created" <tt>Event</tt>.
     */
    public static Event focusCreated(String focusId, EntityBareJid roomName)
    {
        Dictionary<String, Object> props = new Hashtable<>(2);

        props.put(FOCUS_ID_KEY, focusId);
        props.put(ROOM_JID_KEY, roomName);

        return new Event(FOCUS_CREATED_TOPIC, props);
    }

    /**
     * Creates new "focus destroyed" event.
     *
     * @param focusId focus instance identifier.
     * @param roomName MUC room JID for which the focus has been destroyed.
     *
     * @return new "focus destroyed" <tt>Event</tt> instance.
     */
    public static Event focusDestroyed(String focusId, EntityBareJid roomName)
    {
        Dictionary<String, Object> props = new Hashtable<>(2);

        props.put(FOCUS_ID_KEY, focusId);
        props.put(ROOM_JID_KEY, roomName);

        return new Event(FOCUS_DESTROYED_TOPIC, props);
    }

    /**
     * Merges authentication properties into single String transmitted in the
     * event event. After each key name there is colon appended and key/value
     * pairs are separated with CRLF(\r\n).
     *
     * @param properties the map of authentication properties.
     *
     * @return authentication properties map merged into single <tt>String</tt>.
     */
    public static String mergeProperties(Map<String, String> properties)
    {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : properties.entrySet())
        {
            out.append(entry.getKey())
                .append(":")
                .append(entry.getValue())
                .append("\r\n");
        }
        return out.toString();
    }

    /**
     * Splits merged authentication properties <tt>String</tt> into String
     * key/value map.
     *
     * @param merged a <tt>String</tt> that contains merged authentication
     *               properties(with {@link #mergeProperties(Map) method}).
     *
     * @return key/value map of authentication properties.
     */
    public static Map<String, String> splitProperties(String merged)
    {
        String[] entries = merged.split("\r\n");
        Map<String, String> map = new Hashtable<>(entries.length);
        for (String entry : entries)
        {
            if (isBlank(entry))
                continue;

            int colonIdx = entry.indexOf(":");
            String key = entry.substring(0, colonIdx);
            String value = entry.substring(colonIdx + 1);
            map.put(key, value);
        }
        return map;
    }
}
