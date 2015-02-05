/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.jicofo.log;

import org.jitsi.service.configuration.*;
import org.jitsi.videobridge.eventadmin.*;
import org.jitsi.videobridge.influxdb.*;

/**
 * Extends <tt>org.jitsi.videobridge.influxdb.LoggingHandler</tt> with
 * jicofo-specific functionality.
 *
 * @author Boris Grozev
 */
public class LoggingHandler
    extends org.jitsi.videobridge.influxdb.LoggingHandler
{
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
     static final String[] PEER_CONNECTION_STATS_COLUMNS
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
     * Initializes a new <tt>LoggingHandler</tt> instance. Exposes the
     * constructor as public.
     */
    public LoggingHandler(ConfigurationService cfg)
        throws Exception
    {
        super(cfg);
    }

    @Override
    public void handleEvent(Event event)
    {
        if (event == null)
            return;

        String topic = event.getTopic();
        if (EventFactory.CONFERENCE_ROOM_TOPIC.equals(topic))
        {
            //TODO do not use hardcoded keys
            conferenceRoom(event.getProperty("conference_id"),
                           event.getProperty("room_jid"),
                           event.getProperty("focus"));

        }
        else if (EventFactory.PEER_CONNECTION_STATS_TOPIC.equals(topic))
        {
            logEvent(
                (InfluxDBEvent) event.getProperty(EventFactory.EVENT_SOURCE));

        }
        else if (EventFactory.ENDPOINT_DISPLAY_NAME_CHANGED_TOPIC.equals(topic))
        {
            endpointDisplayNameChanged(event.getProperty("conference_id"),
                                       event.getProperty("endpoint_id"),
                                       event.getProperty("display_name"));
        }
        else
        {
            super.handleEvent(event);
        }
    }

    private void conferenceRoom(
            Object conferenceId,
            Object roomJid,
            Object focus)
    {
        logEvent(new InfluxDBEvent("conference_room",
                                   CONFERENCE_ROOM_COLUMNS,
                                   new Object[]
                                           {
                                               conferenceId,
                                               roomJid,
                                               focus
                                           }));
    }

    private void endpointDisplayNameChanged(Object conferenceId,
                                            Object endpointId,
                                            Object displayName)
    {
        logEvent(new InfluxDBEvent("endpoint_display_name",
                                   ENDPOINT_DISPLAY_NAME_COLUMNS,
                                   new Object[]
                                           {
                                                   conferenceId,
                                                   endpointId,
                                                   displayName
                                           }));
    }
}
