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

import net.java.sip.communicator.util.*;
import org.apache.http.*;
import org.apache.http.NameValuePair;
import org.apache.http.client.entity.*;
import org.apache.http.client.methods.*;
import org.apache.http.impl.client.*;
import org.apache.http.message.*;
import org.jitsi.impl.reservation.rest.json.*;
import org.json.simple.parser.*;
import org.json.simple.parser.ParseException;

import java.io.*;
import java.lang.Object;
import java.util.*;

/**
 * Class deals with JSON objects serialization and sending requests to REST
 * API endpoint.
 *
 * @author Pawel Domas
 */
public class ApiHandler
{
    /**
     * The logger
     */
    private static final Logger logger = Logger.getLogger(ApiHandler.class);

    /**
     * Base URL of the REST API.
     */
    private final String baseUrl;

    /**
     * HTTP client used for sending requests.
     */
    private final DefaultHttpClient client = new DefaultHttpClient();

    /**
     * <tt>JSONParser</tt> instance used for parsing JSON.
     */
    private final JSONParser jsonParser = new JSONParser();

    /**
     * JSON handler for <tt>Conference</tt> objects.
     */
    private final ConferenceJsonHandler conferenceJson
            = new ConferenceJsonHandler();

    /**
     * JSON handler for error responses.
     */
    private final ErrorJsonHandler errorJson = new ErrorJsonHandler();

    /**
     * Creates new instance of <tt>ApiHandler</tt>.
     *
     * @param baseUrl the base URL of REST API.
     */
    public ApiHandler(String baseUrl)
    {
        this.baseUrl = baseUrl;
    }

    /**
     * Send Conference POST request to API endpoint which is used for
     * allocating new conferences in reservation system.
     *
     * @param ownerEmail email of new conference owner
     * @param mucRoomName full name of MUC room that is hosting the conference.
     *                    {room_name}@{muc.server.net}
     *
     * @return <tt>ApiResult</tt> that contains system response. It will contain
     *         <tt>Conference</tt> instance filled with data from
     *         the reservation system if everything goes OK.
     *
     * @throws IOException IO exception if connectivity issues have occurred.
     * @throws ParseException parse exception if any problems during JSON
     *         parsing have occurred.
     */
    public ApiResult createNewConference(String ownerEmail, String mucRoomName)
            throws IOException, ParseException
    {
        Conference conference
            = new Conference(mucRoomName, ownerEmail, new Date());

        HttpPost post = new HttpPost(baseUrl + "/conference");

        List<NameValuePair> nameValuePairs = new ArrayList<NameValuePair>(1);
        Map<String, Object> jsonMap = conference.createJSonMap();

        for (Map.Entry<String, Object> entry : jsonMap.entrySet())
        {
            nameValuePairs.add(
                new BasicNameValuePair(
                        entry.getKey(), String.valueOf(entry.getValue())));
        }

        post.setEntity(new UrlEncodedFormEntity(nameValuePairs, "UTF8"));

        logger.info("Sending post: " + jsonMap);

        HttpResponse response = null;
        
        try
        {
            response = client.execute(post);

            int statusCode = response.getStatusLine().getStatusCode();

            logger.info("STATUS CODE: " + statusCode);

            if (200 == statusCode || 201 == statusCode)
            {
                // OK
                readConferenceResponse(conference, response);

                return new ApiResult(statusCode, conference);
            }
            else
            {
                ErrorResponse error = readErrorResponse(response);

                return new ApiResult(statusCode, error);
            }
        }
        finally
        {
            if (response != null && response.getEntity() != null)
            {
                response.getEntity().consumeContent();
            }
        }
    }

    /**
     * Sends Conference GET request to the REST API endpoint. Is used to read
     * info about the conference from the reservation system.
     *
     * @param conferenceId the identifier of the conference for which we want
     *                     to read the data.
     *
     * @return <tt>ApiResult</tt> which describes the response. It will contain
     *         <tt>Conference</tt> if data has been read successfully.
     *
     * @throws IOException if any IO problems occur.
     * @throws ParseException if any problems during JSON parsing occur.
     */
    public ApiResult getConference(Number conferenceId)
            throws IOException, ParseException
    {
        HttpGet get = new HttpGet(baseUrl + "/conference/" + conferenceId);

        HttpResponse response = null;
        
        try
        {
            response = client.execute(get);
    
            int statusCode = response.getStatusLine().getStatusCode();
    
            if (200 == statusCode || 201 == statusCode)
            {
                // OK
                Conference conference
                    = readConferenceResponse(null, response);
    
                return new ApiResult(statusCode, conference);
            }
            else
            {
                ErrorResponse error = readErrorResponse(response);
    
                return new ApiResult(statusCode, error);
            }
        }
        finally 
        {
            if (response != null && response.getEntity() != null)
            {
                response.getEntity().consumeContent();
            }
        }
    }

    /**
     * Sends conference DELETE request to the REST API endpoint.
     *
     * @param conferenceId the identifier of the conference to be destroyed.
     *
     * @return <tt>ApiResult</tt> that describes the response. Check
     *         <tt>ApiResult#statusCode</tt> to see if it went OK.
     *
     * @throws IOException if any IO problems have occurred.
     * @throws ParseException if there were any problems when parsing JSON data
     */
    public ApiResult deleteConference(Number conferenceId)
            throws IOException, ParseException
    {
        HttpDelete delete
            = new HttpDelete(baseUrl + "/conference/" + conferenceId);

        HttpResponse response = null;

        try
        {
            response = client.execute(delete);

            int statusCode = response.getStatusLine().getStatusCode();

            if (200 == statusCode || 201 == statusCode)
            {
                // OK
                return new ApiResult(statusCode);
            }
            else
            {
                ErrorResponse error = readErrorResponse(response);

                return new ApiResult(statusCode, error);
            }
        }
        finally
        {
            if (response != null && response.getEntity() != null)
            {
                response.getEntity().consumeContent();
            }
        }
    }

    /**
     * Parses error response.
     *
     * @param response parsed <tt>ErrorResponse</tt>
     *
     * @return <tt>ErrorResponse</tt> parsed from HTTP content stream.
     *
     * @throws IOException if any IO issues occur.
     * @throws ParseException if any issues with JSON parsing occur.
     */
    private ErrorResponse readErrorResponse(HttpResponse response)
            throws IOException, ParseException
    {
        BufferedReader rd
            = new BufferedReader(
                new InputStreamReader(response.getEntity().getContent()));

        jsonParser.parse(rd, errorJson);

        return errorJson.getResult();
    }

    /**
     * Parses JSON string returned in HTTP response and
     * converts it to <tt>Conference</tt> instance.
     *
     * @param conference <tt>Conference</tt> instance that contains the data
     *                   returned by API endpoint.
     * @param response HTTP response returned by the API endpoint.
     *
     * @return <tt>Conference</tt> instance that contains the data
     *                   returned by API endpoint.
     *
     * @throws IOException if any IO problems occur.
     * @throws ParseException if any problems with JSON parsing occur.
     */
    private Conference readConferenceResponse(Conference conference,
                                              HttpResponse response)
            throws IOException, ParseException
    {
        BufferedReader rd
            = new BufferedReader(
                new InputStreamReader(response.getEntity().getContent()));

        if (conference != null)
        {
            conferenceJson.setForUpdate(conference);
        }

        jsonParser.parse(rd, conferenceJson);

        if (conference == null)
        {
            conference = conferenceJson.getResult();
        }

        logger.info("ID: " + conference.getId());
        logger.info("PIN: " + conference.getPin());
        logger.info("URL: " + conference.getUrl());
        logger.info("SIP ID: " + conference.getSipId());
        logger.info("START TIME: " + conference.getStartTime());
        logger.info("DURATION: " + conference.getDuration());

        return conference;
    }

    /**
     * Structure used for holding processing results. It contains HTTP status
     * code, <tt>Conference</tt> instance which represents the data retrieved
     * from the API or <tt>ErrorResponse</tt> which contains error details.
     */
    static class ApiResult
    {
        /**
         * HTTP status code returned by the API.
         */
        int statusCode;

        /**
         * <tt>Conference</tt> instance filled with data retrieved from the API.
         */
        Conference conference;

        /**
         * <tt>ErrorResponse</tt> which contains API error description.
         */
        ErrorResponse error;

        /**
         * Creates new <tt>ApiResult</tt> insatnce for given HTTP status code.
         *
         * @param statusCode HTTP status code returned by the API endpoint.
         */
        public ApiResult(int statusCode)
        {
            this.statusCode = statusCode;
        }

        /**
         * Creates <tt>ApiResult</tt> which contains <tt>Conference</tt> data
         * read from the API.
         *
         * @param statusCode HTTP status code returned by the API.
         * @param conference <tt>Conference</tt> instance which contains the
         *                   data read from the API.
         */
        public ApiResult(int statusCode, Conference conference)
        {
            this.statusCode = statusCode;
            this.conference = conference;
        }

        /**
         * Creates new <tt>ApiResult</tt> for given <tt>ErrorResponse</tt>.
         *
         * @param statusCode HTTP status code returned by API endpoint.
         * @param error <tt>ErrorResponse</tt> that contains error details
         *              returned by API endpoint.
         */
        public ApiResult(int statusCode, ErrorResponse error)
        {
            this.statusCode = statusCode;
            this.error = error;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public String toString()
        {
            return "ApiError[" + statusCode + "](" + error + ")";
        }
    }
}
