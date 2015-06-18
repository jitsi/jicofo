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
package org.jitsi.impl.reservation.rest;

import org.jitsi.impl.reservation.rest.json.*;
import org.jitsi.protocol.xmpp.util.*;
import org.jitsi.util.*;

import java.util.*;

/**
 * Class represents a conference in reservation system model.
 *
 * @author Pawel Domas
 */
public class Conference
{
    /**
     * Full XMPP MUC room address in the form of room_name@muc.server.net.
     */
    private String mucRoomName;

    /**
     * Conference identifier in the reservation system.
     */
    private Number id;

    /**
     * Conference name(short room name).
     */
    private String name;

    /**
     * Conference owner identifier(email address).
     */
    private String owner;

    /**
     * Conference start time.
     */
    private Date startTime;

    /**
     * Scheduled conference duration in seconds.
     */
    private long duration = -1;

    /**
     * FIXME: not used
     * Conference room URL.
     */
    private String url;

    /**
     * FIXME: not used
     * Conference room pin password.
     */
    private String pin;

    /**
     * FIXME: not used
     * Sip gateway instance identifier.
     */
    private Number sipId;

    /**
     * Creates new empty instance of <tt>Conference</tt>.
     */
    public Conference()
    {

    }

    /**
     * Creates new <tt>Conference</tt> instance for given parameters.
     *
     * @param mucRoomName full XMPP MUC room address in the form of
     *                    room_name@muc.server.net. Short room name is extracted
     *                    and stored in {@link #name} field.
     * @param owner conference owner identifier(email address).
     * @param startTime conference start time(date).
     */
    public Conference(String mucRoomName, String owner, Date startTime)
    {
        this.setMucRoomName(mucRoomName);
        this.name = MucUtil.extractName(mucRoomName);
        this.owner = owner;
        this.startTime = startTime;
    }

    /**
     * Returns full XMPP MUC room address that hosts the conference, in the
     * form of room_name@muc.server.net.
     */
    public String getMucRoomName()
    {
        return mucRoomName;
    }

    /**
     * Sets full XMPP MUC room address that hosts this conference.
     * @param mucRoomName MUC address in the form of: room_name@muc.server.net
     */
    public void setMucRoomName(String mucRoomName)
    {
        this.mucRoomName = mucRoomName;
    }

    /**
     * Sets conference identifier obtained form the reservation system.
     * @param id conference identifier form the reservation system.
     */
    public void setId(Number id)
    {
        this.id = id;
    }

    /**
     * Returns conference identifier assigned by the reservation system.
     */
    public Number getId()
    {
        return id;
    }

    /**
     * Sets the URL that points to the conference location on the web.
     * @param url web address of this conference room.
     */
    public void setUrl(String url)
    {
        this.url = url;
    }

    /**
     * Returns the URL(web location) of this conference room.
     */
    public String getUrl()
    {
        return url;
    }

    /**
     * Sets conference pin password on this instance.
     * @param pin password pin to be set on this instance.
     */
    public void setPin(String pin)
    {
        this.pin = pin;
    }

    /**
     * Returns conference pin password assigned for this conference.
     */
    public String getPin()
    {
        return pin;
    }

    /**
     * Sets the SIP gateway id assigned for this conference.
     * @param sipId SIP gateway instance identifier assigned by the
     *              reservation system for this conference.
     */
    public void setSipId(Number sipId)
    {
        this.sipId = sipId;
    }

    /**
     * Returns SIP gateway identifier assigned for this conference by the
     * reservation system.
     */
    public Number getSipId()
    {
        return sipId;
    }

    /**
     * Returns the name of the conference.
     */
    public String getName()
    {
        return name;
    }

    /**
     * Sets conference name.
     * @param name the name of the conference to set.
     */
    public void setName(String name)
    {
        this.name = name;
    }

    /**
     * Returns conference owner identifier(email).
     */
    public String getOwner()
    {
        return owner;
    }

    /**
     * Sets conference owner identifier.
     * @param owner identifier of the conference owner to set(email address)
     */
    public void setOwner(String owner)
    {
        this.owner = owner;
    }

    /**
     * Returns conference start time(<tt>Date</tt>).
     */
    public Date getStartTime()
    {
        return startTime;
    }

    /**
     * Sets conference start time.
     * @param startTime a <tt>Date</tt> which will be set as conference start
     *                  time.
     */
    public void setStartTime(Date startTime)
    {
        this.startTime = startTime;
    }

    /**
     * Returns scheduled conference duration in seconds.
     */
    public long getDuration()
    {
        return duration;
    }

    /**
     * Sets scheduled conference duration.
     * @param duration duration in seconds to set.
     */
    public void setDuration(long duration)
    {
        this.duration = duration;
    }

    /**
     * Creates map of conference properties which describe this
     * <tt>Conference</tt> instance.
     *
     * @return the map of JSON ket to object values which describe this
     *         <tt>Conference</tt> instance.
     */
    public Map<String, Object> createJSonMap()
    {
        Map<String, Object> obj = new LinkedHashMap<String, Object>();
        putObject(obj, ConferenceJsonHandler.CONF_ID_KEY, id);
        putString(obj, ConferenceJsonHandler.CONF_NAME_KEY, name);
        putString(obj, ConferenceJsonHandler.CONF_OWNER_KEY, owner);
        putDate(obj, ConferenceJsonHandler.CONF_START_TIME_KEY, startTime);
        putObject(obj, ConferenceJsonHandler.CONF_DURATION_KEY, duration);
        putString(obj, ConferenceJsonHandler.CONF_URL_KEY, url);
        putObject(obj, ConferenceJsonHandler.CONF_PIN_KEY, pin);
        putObject(obj, ConferenceJsonHandler.CONF_SIP_ID_KEY, sipId);
        return obj;
    }

    /**
     * Converts given time in millis into string representation in format
     * HH:MM. Value will be stored only if it's greater or equal to zero.
     *
     * @param map destination object map
     * @param key key name under which output string will be stored.
     * @param time time in millis to be converted into string
     */
    private void putTime(Map<String, Object> map, String key, long time)
    {
        if (time >= 0)
        {
            long hours = time / 3600000L;
            long minutes = (time % 3600000L) / 60000L;
            map.put(key, String.format("%1$02d:%2$02d", hours, minutes));
        }
    }

    /**
     * Formats given <tt>Date</tt> using {@link ConferenceJsonHandler
     * #DATE_FORMAT} and stores it in output JSON object map fi given value
     * is not null.
     *
     * @param map output JSON object map.
     * @param key JSON object key which will be used to store output value.
     * @param date the <tt>Date</tt> which will be stored as JSON String.
     */
    private void putDate(Map<String, Object> map, String key, Date date)
    {
        if (date != null)
        {
            map.put(key, ConferenceJsonHandler.DATE_FORMAT.format(date));
        }
    }

    /**
     * Puts object into JSON map given that it's value is not <tt>null</tt>.
     *
     * @param map output JSON object map.
     * @param key JSON key under which the value will be stored.
     * @param value <tt>Object</tt> value which will be stored in output JSON
     *              map.
     */
    private void putObject(Map<String, Object> map, String key, Object value)
    {
        if (value != null)
        {
            map.put(key, value);
        }
    }

    /**
     * Stores given <tt>String</tt> in output JSON object map if it's value
     * is not <tt>null</tt> nor empty.
     *
     * @param map output JSON Object map.
     * @param key JSON key under which output value will be stored.
     * @param value <tt>String</tt> to be stored in JSON map.
     */
    private void putString(Map<String, Object> map, String key, String value)
    {
        if (!StringUtils.isNullOrEmpty(value))
        {
            map.put(key, value);
        }
    }
}
