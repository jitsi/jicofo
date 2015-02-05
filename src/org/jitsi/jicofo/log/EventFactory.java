/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.jicofo.log;

import org.jitsi.util.*;
import org.jitsi.videobridge.eventadmin.*;
import org.jitsi.videobridge.influxdb.*;
import org.json.simple.*;
import org.json.simple.parser.*;

import java.util.*;

/**
 * A utility class with static methods which initialize <tt>Event</tt> instances
 * with pre-determined fields.
 *
 * @author Boris Grozev
 */
public class EventFactory
    extends org.jitsi.videobridge.EventFactory
{
    /**
     * The logger instance used by this class.
     */
    private final static Logger logger
            = Logger.getLogger(EventFactory.class);

    /**
     * The name of the topic of a "conference room" event.
     */
    public static final String CONFERENCE_ROOM_TOPIC
            = "org/jitsi/jicofo/CONFERENCE_ROOM_CREATED";

    public static final String PEER_CONNECTION_STATS_TOPIC
            = "org/jitsi/jicofo/PEER_CONNECTION_STATS";

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
        // TODO do not use hard-coded keys
        Dictionary properties = new Hashtable(3);
        properties.put("conference_id", conferenceId);
        properties.put("endpoint_id", endpointId);
        properties.put("display_name", displayName);
        return new Event(ENDPOINT_DISPLAY_NAME_CHANGED_TOPIC,
                         properties);
    }

    /**
     * Creates an Event after parsing <tt>stats</tt> as JSON in the format
     * used in Jitsi Meet.
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

        InfluxDBEvent influxDBEvent = new InfluxDBEvent("peer_connection_stats",
                                LoggingHandler.PEER_CONNECTION_STATS_COLUMNS,
                                values);

        // We specifically add a "time" column
        influxDBEvent.setUseLocalTime(false);


        return new Event(
                PEER_CONNECTION_STATS_TOPIC, makeProperties(influxDBEvent));
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

        JSONParser parser = new JSONParser();
        JSONObject jsonObject = (JSONObject) parser.parse(statsStr);

        List<Object[]> values = new LinkedList<Object[]>();
        JSONArray timestamps = (JSONArray) jsonObject.get("timestamps");
        JSONObject stats = (JSONObject) jsonObject.get("stats");

        for (Object groupName : stats.keySet())
        {
            JSONObject group = ((JSONObject) stats.get(groupName));
            Object type = group.get("type");
            for (Object statName : group.keySet())
            {
                if ("type".equals(statName))
                    continue;

                JSONArray statValues = (JSONArray)group.get(statName);
                for (int i = 0; i < statValues.size(); i++)
                {
                    Object[] point
                        = new Object[LoggingHandler
                            .PEER_CONNECTION_STATS_COLUMNS.length];

                    point[0] = timestamps.get(i);
                    point[1] = conferenceId;
                    point[2] = endpointId;
                    point[3] = groupName;
                    point[4] = statName;
                    point[5] = type;
                    point[6] = statValues.get(i);

                    values.add(point);
                }
            }
        }

        return values.toArray();
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
            String focus)
    {
        // TODO do not use hard-coded keys
        Dictionary properties = new Hashtable(3);
        properties.put("conference_id", conferenceId);
        properties.put("room_jid", roomJid);
        properties.put("focus", focus);
        return new Event(CONFERENCE_ROOM_TOPIC, properties);
    }
}

