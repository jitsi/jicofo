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
package org.jitsi.jicofo.log;

import org.jitsi.eventadmin.*;
import org.jitsi.influxdb.*;
import org.jitsi.util.*;
import org.json.simple.*;
import org.json.simple.parser.*;

import java.util.*;

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
     * The logger instance used by this class.
     */
    private final static Logger logger = Logger.getLogger(EventFactory.class);

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
     * The name of the topic of a "peer connection stats" event.
     */
    public static final String PEER_CONNECTION_STATS_TOPIC
        = "org/jitsi/jicofo/PEER_CONNECTION_STATS";

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
     * Creates a new "endpoint display name changed" <tt>Event</tt>, which
     * conference ID to the JID of the associated MUC.
     *
     * @param conferenceId the ID of the COLIBRI conference.
     * @param endpointId the ID of the COLIBRI endpoint.
     * @param displayName the new display name.
     *
     * @return the <tt>Event</tt> which was created.
     */
    public static Event endpointDisplayNameChanged(
            String conferenceId,
            String endpointId,
            String displayName)
    {
        Dictionary<String, Object> props = new Hashtable<>(3);

        props.put(CONFERENCE_ID_KEY, conferenceId);
        props.put(ENDPOINT_ID_KEY, endpointId);
        props.put(DISPLAY_NAME_KEY, displayName);

        return new Event(ENDPOINT_DISPLAY_NAME_CHANGED_TOPIC, props);
    }

    /**
     * Creates an Event after parsing <tt>stats</tt> as JSON in the format
     * used in Jitsi Meet.
     *
     * @param conferenceId the ID of the conference.
     * @param endpointId the ID of the endpoint.
     * @param stats the string representation of
     * @return
     */
    public static Event peerConnectionStats(
            String conferenceId,
            String endpointId,
            String stats)
    {
        Object[] values;

        try
        {
            values = parsePeerConnectionStats(conferenceId, endpointId, stats);
        }
        catch (Exception e)
        {
            logger.warn("Failed to parse PeerConnection stats JSON: " + e);
            return null;
        }

        InfluxDBEvent influxDBEvent
            = new InfluxDBEvent(
                    "peer_connection_stats",
                    LoggingHandler.PEER_CONNECTION_STATS_COLUMNS,
                    values);

        // We specifically add a "time" column
        influxDBEvent.setUseLocalTime(false);

        return
            new Event(
                    PEER_CONNECTION_STATS_TOPIC,
                    makeProperties(influxDBEvent));
    }

    /**
     * Parses <tt>statsStr</tt> as JSON in the format used by Jitsi Meet, and
     * returns an Object[][] containing the values to be used in an
     * <tt>Event</tt> for the given JSON.
     * @param conferenceId the value to use for the conference_id field.
     * @param endpointId the value to use for the endpoint_id field.
     * @param statsStr the PeerConnection JSON string.
     * @return an Object[][] containing the values to be used in an
     * <tt>Event</tt> for the given JSON.
     * @throws Exception if parsing fails for any reason.
     */
    private static Object[] parsePeerConnectionStats(
            String conferenceId,
            String endpointId,
            String statsStr)
        throws Exception
    {
        // An example JSON in the format that we expect:
        // {
        //   "timestamps": [1, 2, 3],
        //   "stats": {
        //      "group1": {
        //          "type": "some string",
        //          "stat1": ["some", "values", ""]
        //          "stat2": ["some", "more", "values"]
        //      },
        //      "bweforvideo": {
        //          "type":"VideoBwe",
        //          "googActualEncBitrate": ["12","34","56"],
        //          "googAvailableSendBandwidth": ["78", "90", "12"]
        //      }
        //   }
        // }

        // time: 1
        // conference_id: blabla
        // endpoint_id: blabla
        // value: [
        //     ["group1", "some string", {"stat1": "some", "stat2": "some"}],
        //     ["bweforvideo", "VideoBwe", {"googActualEncBitrate": "12", "googAvailableSendBandwidth": "78"}]
        // ]

        JSONParser parser = new JSONParser();
        JSONObject jsonObject = (JSONObject) parser.parse(statsStr);

        List<Object[]> values = new LinkedList<>();
        JSONArray timestamps = (JSONArray) jsonObject.get("timestamps");
        JSONObject stats = (JSONObject) jsonObject.get("stats");

        for (int i = 0; i < timestamps.size(); i++)
        {
            long timestamp = (Long) timestamps.get(i);

            JSONArray value = new JSONArray();
            for (Object groupName : stats.keySet())
            {
                JSONArray groupValue = new JSONArray();
                JSONObject group = (JSONObject) stats.get(groupName);
                Object type = group.get("type");

                groupValue.add(groupName);
                groupValue.add(type);

                JSONObject s = new JSONObject();
                for (Object statName : group.keySet())
                {
                    if ("type".equals(statName))
                        continue;

                    JSONArray statValues = (JSONArray) group.get(statName);
                    s.put(statName, statValues.get(i));
                }
                groupValue.add(s);

                value.add(groupValue);
            }
            Object[] point
                = new Object[LoggingHandler.PEER_CONNECTION_STATS_COLUMNS.length];

            point[0] = timestamp;
            point[1] = conferenceId;
            point[2] = endpointId;
            point[3] = value.toJSONString();

            values.add(point);
        }

        return values.toArray();
    }

    /**
     * Creates new <tt>Event</tt> for {@link #FOCUS_JOINED_ROOM_TOPIC}.
     *
     * @param roomJid the full address of MUC room.
     * @param focusId focus instance identifier.
     *
     * @return new <tt>Event</tt> for {@link #FOCUS_JOINED_ROOM_TOPIC}.
     */
    public static Event focusJoinedRoom(
            String roomJid,
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
     * @return the <tt>Event</tt> which was created.
     */
    public static Event conferenceRoom(
            String conferenceId,
            String roomJid,
            String focus,
            String bridgeJid)
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
    public static Event focusCreated(String focusId, String roomName)
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
    public static Event focusDestroyed(String focusId, String roomName)
    {
        Dictionary<String, Object> props = new Hashtable<>(2);

        props.put(FOCUS_ID_KEY, focusId);
        props.put(ROOM_JID_KEY, roomName);

        return new Event(FOCUS_DESTROYED_TOPIC, props);
    }

    /**
     * Merges authentication properties into single String transmitted in the
     * log event. After each key name there is colon appended and key/value
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
            if (StringUtils.isNullOrEmpty(entry))
                continue;

            int colonIdx = entry.indexOf(":");
            String key = entry.substring(0, colonIdx);
            String value = entry.substring(colonIdx + 1);
            map.put(key, value);
        }
        return map;
    }
}
