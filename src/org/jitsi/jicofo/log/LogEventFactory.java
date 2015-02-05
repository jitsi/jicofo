/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.jicofo.log;

import org.jitsi.util.*;
import org.jitsi.videobridge.log.*;
import org.json.simple.*;
import org.json.simple.parser.*;

import java.util.*;

/**
 * A utility class with static methods which initialize <tt>Event</tt> instances
 * with pre-determined fields.
 *
 * @author Boris Grozev
 */
public class LogEventFactory
{
    /**
     * The logger instance used by this class.
     */
    private final static Logger logger
            = Logger.getLogger(LogEventFactory.class);

    /**
     * The names of the columns of a "focus created" event.
     */
    private static final String[] FOCUS_CREATED_COLUMNS
            = new String[]
            {
                    "room_jid"
            };

    /**
     * The names of the columns of an "endpoint display name" event.
     */
    private static final String[] ENDPOINT_DISPLAY_NAME_COLUMNS
            = new String[]
            {
                    "conference_id",
                    "endpoint_id",
                    "display_name"
            };

    /**
     * The names of the columns of a "peer connection stats" event.
     */
    private static final String[] PEER_CONNECTION_STATS_COLUMNS
            = new String[]
            {
                    "time",
                    "conference_id",
                    "endpoint_id",
                    "group_name",
                    "type",
                    "stat",
                    "value"
            };

    /**
     * The names of the columns of a "conference room" event.
     */
    private static final String[] CONFERENCE_ROOM_COLUMNS
            = new String[]
            {
                    "conference_id",
                    "room_jid",
                    "focus"
            };

    /**
     * Creates a new "focus created" <tt>Event</tt>.
     * @param roomJid the JID of the MUC for which the focus was created.
     *
     * @return the <tt>Event</tt> which was created.
     */
    public static Event focusCreated(
            String roomJid)
    {
        return new Event("focus_created",
                         FOCUS_CREATED_COLUMNS,
                         new Object[]
                                 {
                                         roomJid,
                                 });
    }

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
        return new Event("endpoint_display_name",
                         ENDPOINT_DISPLAY_NAME_COLUMNS,
                         new Object[]
                                 {
                                         conferenceId,
                                         endpointId,
                                         displayName
                                 });
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

        Event event = new Event("peer_connection_stats",
                                PEER_CONNECTION_STATS_COLUMNS,
                                values);

        // We specifically add a "time" column
        event.setUseLocalTime(false);

        return event;
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
                        = new Object[PEER_CONNECTION_STATS_COLUMNS.length];

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
        return new Event("conference_room",
                         CONFERENCE_ROOM_COLUMNS,
                         new Object[]
                                 {
                                         conferenceId,
                                         roomJid,
                                         focus
                                 });
    }
}

