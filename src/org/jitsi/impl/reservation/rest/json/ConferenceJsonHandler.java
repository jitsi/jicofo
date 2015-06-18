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
package org.jitsi.impl.reservation.rest.json;

import net.java.sip.communicator.util.*;
import org.jitsi.impl.reservation.rest.*;
import org.json.simple.parser.*;
import org.json.simple.parser.ParseException;

import java.io.*;
import java.text.*;

/**
 * {@link ContentHandler} implementation for parsing Conference JSON.
 *
 * @author Pawel Domas
 */
public class ConferenceJsonHandler
    extends AbstractJsonHandler<Conference>
{
    /**
     * The logger.
     */
    private final static Logger logger
        = Logger.getLogger(ConferenceJsonHandler.class);

    /**
     * The key for conference identifier.
     */
    public static final String CONF_ID_KEY = "id";

    /**
     * The key for conference name.
     */
    public static final String CONF_NAME_KEY = "name";

    /**
     * The key for conference owner email.
     */
    public static final String CONF_OWNER_KEY = "mail_owner";

    /**
     * The key for conference start time.
     */
    public static final String CONF_START_TIME_KEY = "start_time";

    /**
     * The key for conference duration.
     */
    public static final String CONF_DURATION_KEY = "duration";

    /**
     * The key for conference URL.
     */
    public static final String CONF_URL_KEY = "url";

    /**
     * The key for conference PIN.
     */
    public static final String CONF_PIN_KEY = "pin";

    /**
     * The key for SIP gateway instance identifier.
     */
    public static final String CONF_SIP_ID_KEY = "sip_id";

    /**
     * Date format used to parse/serialize dates.
     */
    public static final SimpleDateFormat DATE_FORMAT
            = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSX");

    /**
     * {@inheritDoc}
     */
    @Override
    protected Conference createNewObject()
    {
        return new Conference();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean primitive(Object primitive)
            throws ParseException, IOException
    {
        if (CONF_ID_KEY.equals(currentKey))
        {
            assertNumber(primitive);

            editedInstance.setId((Number)primitive);
        }
        else if (CONF_NAME_KEY.equals(currentKey))
        {
            assertString(primitive);

            if (checkImmutableString(
                    editedInstance.getName(),
                    (String) primitive, CONF_NAME_KEY))
            {
                editedInstance.setName((String) primitive);
            }
        }
        else if (CONF_OWNER_KEY.equals(currentKey))
        {
            if (checkImmutableString(
                    editedInstance.getOwner(),
                    (String) primitive, CONF_OWNER_KEY))
            {
                editedInstance.setOwner((String) primitive);
            }
        }
        else if (CONF_URL_KEY.equals(currentKey))
        {
            assertString(primitive);

            editedInstance.setUrl((String)primitive);
        }
        else if (CONF_PIN_KEY.equals(currentKey))
        {
            assertString(primitive);

            editedInstance.setPin((String)primitive);
        }
        else if (CONF_START_TIME_KEY.equals(currentKey))
        {
            assertString(primitive);

            try
            {
                editedInstance.setStartTime(
                        DATE_FORMAT.parse((String) primitive));
            }
            catch (java.text.ParseException e)
            {
                logger.error(e, e);

                throw new ParseException(
                        ParseException.ERROR_UNEXPECTED_TOKEN, primitive);
            }
        }
        else if (CONF_DURATION_KEY.equals(currentKey))
        {
            assertNumber(primitive);

            editedInstance.setDuration((Long) primitive);
        }
        else if (CONF_SIP_ID_KEY.equals(currentKey))
        {
            if (primitive instanceof Number)
            {
                editedInstance.setSipId((Number) primitive);
            }
            else
            {
                throw new ParseException(
                    ParseException.ERROR_UNEXPECTED_TOKEN, primitive);
            }
        }
        return true;
    }
}
