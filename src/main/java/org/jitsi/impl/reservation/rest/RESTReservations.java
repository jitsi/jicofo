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

import net.java.sip.communicator.util.Logger;
import org.jitsi.assertions.*;
import org.jitsi.jicofo.*;
import org.jitsi.jicofo.reservation.*;
import org.json.simple.parser.*;

import java.io.*;
import java.util.*;

/**
 * Implements {@link ReservationSystem} in order to integrate with REST API of
 * the reservation system.<br/> Creates/destroys conferences via API endpoint
 * and also enforces scheduled conference duration.
 *
 * @author Pawel Domas
 */
public class RESTReservations
    implements ReservationSystem, FocusManager.FocusAllocationListener
{
    /**
     * The logger.
     */
    private final static Logger logger
        = Logger.getLogger(RESTReservations.class);

    /**
     * Configuration property name which specifies REST API base URL.
     */
    public static final String API_BASE_URL_PNAME
        = "org.jitsi.impl.reservation.rest.BASE_URL";

    /**
     * Focus manager instance.
     */
    private FocusManager focusManager;

    /**
     * How often do we verify conference duration ?
     */
    private final long EXPIRE_INTERVAL = 5000;

    /**
     * Timer thread that validates conference duration.
     */
    private Timer confDurationGuard;

    /**
     * Active conferences known to our side.
     */
    private final Map<String, Conference> conferenceMap = new HashMap<>();

    /**
     * Utility class that deals with API REST request processing.
     */
    private final ApiHandler api;

    /**
     * Creates new instance of <tt>RESTReservations</tt> instance.
     * @param baseUrl base URL for RESP API endpoint.
     */
    public RESTReservations(String baseUrl)
    {
        Assert.notNullNorEmpty(baseUrl, "baseUrl: " + baseUrl);

        this.api = new ApiHandler(baseUrl);
    }

    /**
     * Initializes this instance and starts background tasks required by
     * <tt>RESTReservations</tt> to work properly.
     *
     * @param focusManager <tt>FocusManager</tt> instance that manages
     *                     conference pool.
     */
    public void start(FocusManager focusManager)
    {
        if (this.focusManager != null)
            throw new IllegalStateException("already started");

        Assert.notNull(focusManager, "focusManager");

        this.focusManager = focusManager;
        focusManager.setFocusAllocationListener(this);

        confDurationGuard = new Timer("ConferenceDuartionGuard");
        confDurationGuard.scheduleAtFixedRate(
            new ConferenceExpireTask(), EXPIRE_INTERVAL, EXPIRE_INTERVAL);
    }

    /**
     * Stops this instance and all threads created by it.
     */
    public void stop()
    {
        if (focusManager != null)
        {
            focusManager.setFocusAllocationListener(null);
            focusManager = null;
        }
        if (confDurationGuard != null)
        {
            confDurationGuard.cancel();
            confDurationGuard = null;
        }
    }

    /**
     * {@inheritDoc}
     */
    public synchronized Result createConference(String creator,
                                                String mucRoomName)
    {
        Conference conference = conferenceMap.get(mucRoomName);
        if (conference == null)
        {
            // Create new conference
            try
            {
                ApiHandler.ApiResult result
                    = api.createNewConference(creator, mucRoomName);

                if (result.error == null)
                {
                    conference = result.conference;
                    conferenceMap.put(mucRoomName, conference);
                }
                else if (result.statusCode == 409
                        && result.error.getConflictId() != null)
                {
                    Number conflictId = result.error.getConflictId();

                    // Conference already exists(check if we have it locally)
                    conference = findConferenceForId(conflictId);
                    
                    logger.info(
                        "Conference '" + mucRoomName + "' already "
                            + "allocated, id: " + conflictId);
                    
                    // do GET conflict conference
                    if (conference == null)
                    {
                        ApiHandler.ApiResult getResult
                            = api.getConference(conflictId);
                        if (getResult.conference != null)
                        {
                            conference = getResult.conference;
                            // Fill full room name as it is not transferred
                            // over REST API
                            conference.setMucRoomName(mucRoomName);

                            conferenceMap.put(mucRoomName, conference);
                        }
                        else
                        {
                            logger.error("API error: " + result);
                            return new Result(
                                RESULT_INTERNAL_ERROR,
                                result.error.getMessage());
                        }
                    }
                }
                else
                {
                    // Other error
                    logger.error("API error: " + result);
                    return new Result(
                            RESULT_INTERNAL_ERROR, result.error.getMessage());
                }
            }
            catch (IOException e)
            {
                logger.error(e, e);
                return new Result(RESULT_INTERNAL_ERROR, e.getMessage());
            }
            catch (ParseException e)
            {
                logger.error(e, e);
                return new Result(RESULT_INTERNAL_ERROR, e.getMessage());
            }
        }

        // Verify owner == creator
        if (creator.equals(conference.getOwner()))
        {
            return new Result(RESULT_OK);
        }
        else
        {
            logger.error(
                "Room " + mucRoomName + ", conflict : "
                        + creator + " != " + conference.getOwner());
            
            return new Result(RESULT_CONFLICT);
        }
    }

    /**
     * Deletes conference for given <tt>mucRoomName</tt> through the API.
     */
    private synchronized Result deleteConference(String mucRoomName)
    {
        Conference conference = conferenceMap.get(mucRoomName);
        if (conference != null)
        {
            // Delete conference
            Number id = conference.getId();

            int result = deleteConference(id);

            if (result == RESULT_OK)
            {
                conferenceMap.remove(mucRoomName);
            }
            else
            {
                // Other error
                return new Result(result);
            }
        }
        return new Result(RESULT_OK);
    }

    /**
     * Deletes conference from the reservation system.
     *
     * @param id identifier of the conference which has to be removed.
     *
     * @return one of {@link ReservationSystem} "result" constants.
     */
    private int deleteConference(Number id)
    {
        // Delete conference
        try
        {
            logger.info("Deleting conference: " + id);
            ApiHandler.ApiResult result = api.deleteConference(id);
            if (result.statusCode == 200)
            {
                logger.info("Conference " + id + " deleted - OK");
                return RESULT_OK;
            }
            else
            {
                logger.error("DELETE API ERROR: " + result);
            }
        }
        catch (IOException e)
        {
            logger.error(e, e);
        }
        catch (ParseException e)
        {
            logger.error(e, e);
        }
        return RESULT_INTERNAL_ERROR;
    }

    /**
     * Finds conference for given ID assigned by the reservation system.
     *
     * @param id identifier of the conference to find.
     *
     * @return <tt>Conference</tt> for given <tt>id</tt> or <tt>null</tt>
     *         if not found.
     */
    private synchronized Conference findConferenceForId(Number id)
    {
        for (Conference conference : conferenceMap.values())
        {
            if (conference.getId().equals(id))
            {
                return conference;
            }
        }
        return null;
    }

    /**
     * Implements in order to listen for ended conferences and remove them from
     * the reservation system.
     *
     * {@inheritDoc}
     */
    @Override
    synchronized public void onFocusDestroyed(String roomName)
    {
        //roomName = MucUtil.extractName(roomName);

        // Focus destroyed
        Conference conference = conferenceMap.get(roomName);
        if (conference == null)
        {
            logger.info("Conference " + roomName +" already destroyed");
            return;
        }

        Result result = deleteConference(roomName);
        if (result.getCode() == RESULT_OK)
        {
            logger.info(
                "Deleted conference from the reservation system: " + roomName);
        }
        else
        {
            logger.error(
                "Failed to delete room: " + roomName+", error code: " + result);
        }
    }

    /**
     * Timer task that enforces scheduled conference duration and destroys
     * conferences which exceed assigned time limit. Run in
     * {@link #EXPIRE_INTERVAL} time intervals.
     */
    class ConferenceExpireTask extends TimerTask
    {
        @Override
        public void run()
        {
            synchronized (RESTReservations.this)
            {
                Iterator<Conference> conferenceIterator
                    = conferenceMap.values().iterator();

                while (conferenceIterator.hasNext())
                {
                    Conference conference = conferenceIterator.next();
                    Date startTimeDate = conference.getStartTime();
                    if (startTimeDate == null)
                    {
                        logger.error(
                            "No 'start_time' for conference: "
                                    + conference.getName());
                        continue;
                    }
                    long startTime = startTimeDate.getTime();
                    long duration = conference.getDuration();
                    // Convert duration to millis
                    duration = duration * 1000L;
                    long now = System.currentTimeMillis();
                    if (now - startTime > duration - EXPIRE_INTERVAL)
                    {
                        // Destroy the conference
                        String mucRoomName = conference.getMucRoomName();

                        deleteConference(conference.getId());

                        conferenceIterator.remove();

                        focusManager.destroyConference(
                            mucRoomName,
                            "Scheduled conference duration exceeded.");
                    }
                }
            }
        }
    }
}
