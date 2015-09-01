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

import java.io.*;
import net.java.sip.communicator.util.*;
import org.apache.http.*;
import org.apache.http.NameValuePair;
import org.apache.http.client.*;
import org.apache.http.client.entity.*;
import org.apache.http.client.methods.*;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.message.*;
import org.jitsi.impl.reservation.rest.json.*;
import org.json.simple.parser.*;
import org.json.simple.parser.ParseException;

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
     *  Default maximum retries number for requests on conference create
     *  API endpoint
     */
    private final int CONFERENCE_CREATE_MAX_RETRIES = 8;

    /**
     *  Default maximum retries number for requests on conference create
     *  API endpoint
     */
    private final int CONFERENCE_RESOLVE_CONFLICT_RETRIES = 8;

    /**
     *  Default maximum retries number for requests on conference create
     *  API endpoint
     */
    private final int CONFERENCE_DELETE_MAX_RETRIES = 13;

    /**
     * The logger
     */
    private static final Logger logger = Logger.getLogger(ApiHandler.class);

    /**
     * Base URL of the REST API.
     */
    private final String baseUrl;

    /**
     *  Apache HTTP client instance used for communicating
     *  with reservation REST API over HTTP
     */
    private final HttpClient httpClient;

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
     * Maximum retry number for create conference API endpoint
     */
    private final int retriesCreate;

    /**
     * Maximum retry number for resolve conflict conference API endpoint
     */
    private final int retriesResolveConflict;

    /**
     * Maximum retry number for delete conference API endpoint
     */
    private final int retriesDelete;


    /**
     * Creates new instance of <tt>ApiHandler</tt>.
     *
     * @param baseUrl the base URL of REST API.
     */
    public ApiHandler(String baseUrl)
    {
        this.baseUrl = baseUrl;
        this.httpClient = new DefaultHttpClient();
        this.retriesCreate = CONFERENCE_CREATE_MAX_RETRIES;
        this.retriesResolveConflict = CONFERENCE_RESOLVE_CONFLICT_RETRIES;
        this.retriesDelete = CONFERENCE_DELETE_MAX_RETRIES;
    }

    /**
     * Creates new instance of <tt>ApiHandler</tt> with custom HTTP client
     */
    public ApiHandler(String baseUrl, HttpClient httpClient)
    {
        this.baseUrl = baseUrl;
        this.httpClient = httpClient;
        this.retriesCreate = CONFERENCE_CREATE_MAX_RETRIES;
        this.retriesResolveConflict = CONFERENCE_RESOLVE_CONFLICT_RETRIES;
        this.retriesDelete = CONFERENCE_DELETE_MAX_RETRIES;
    }

    /**
     * Creates new instance of <tt>ApiHandler</tt>
     * with custom retries numbers for API endpoints
     */
    public ApiHandler(String baseUrl, HttpClient httpClient, int retriesCreate,
                      int retriesResolveConflict, int retriesDelete)
    {
        this.baseUrl = baseUrl;
        this.httpClient = httpClient;
        this.retriesCreate = retriesCreate;
        this.retriesDelete = retriesDelete;
        this.retriesResolveConflict = retriesResolveConflict;
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
     * @throws FaultTolerantRESTRequest.RetryExhaustedException
     *         When the number of retries to submit the request
     *         for the conference data is reached
     * @throws UnsupportedEncodingException
     *          When the room data have the encoding that does not play
     *          with UTF8 standard
     */
    public ApiResult createNewConference(String ownerEmail, String mucRoomName)
            throws
            FaultTolerantRESTRequest.RetryExhaustedException,
            UnsupportedEncodingException
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

        CreateConferenceResponseParser conferenceResponseParser
                = new CreateConferenceResponseParser(conference);

        FaultTolerantRESTRequest faultTolerantRESTRequest =
                new FaultTolerantRESTRequest(post, conferenceResponseParser,
                        retriesCreate, httpClient);

        return faultTolerantRESTRequest.submit();
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
     * @throws FaultTolerantRESTRequest.RetryExhaustedException
     *         when the number of retries to submit the request
     *         for the conference data is reached
     */
    public ApiResult getConference(Number conferenceId)
            throws FaultTolerantRESTRequest.RetryExhaustedException
    {
        HttpGet get = new HttpGet(baseUrl + "/conference/" + conferenceId);

        GetConferenceResponseParser conferenceResponseParser
                = new GetConferenceResponseParser();

        FaultTolerantRESTRequest faultTolerantRESTRequest
                = new FaultTolerantRESTRequest(get, conferenceResponseParser,
                        retriesResolveConflict, httpClient);

        return faultTolerantRESTRequest.submit();

    }

    /**
     * Sends conference DELETE request to the REST API endpoint.
     *
     * @param conferenceId the identifier of the conference to be destroyed.
     *
     * @return <tt>ApiResult</tt> that describes the response. Check
     *         <tt>ApiResult#statusCode</tt> to see if it went OK.
     *
     * @throws FaultTolerantRESTRequest.RetryExhaustedException
     *         When the number of retries to submit the request
     *         for the conference data is reached
     */
    public ApiResult deleteConference(Number conferenceId)
            throws FaultTolerantRESTRequest.RetryExhaustedException
    {
        HttpDelete delete
            = new HttpDelete(baseUrl + "/conference/" + conferenceId);

        DeleteConferenceResponseParser conferenceResponseParser
                = new DeleteConferenceResponseParser();

        FaultTolerantRESTRequest faultTolerantRESTRequest
                = new FaultTolerantRESTRequest(delete, conferenceResponseParser,
                        retriesDelete, httpClient);

        return faultTolerantRESTRequest.submit();

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
            throws IOException,
            ParseException
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
            throws IOException,
            ParseException
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
     * This class will be used to parse the response for room
     * deletion reservation callback.
     */
    protected class DeleteConferenceResponseParser
            extends AbstractRESTResponseParser
    {

        /**
         *
         * @param response The Apache HC client response
         * @throws org.json.simple.parser.ParseException
         */
        @Override
        protected void parse(HttpResponse response)
                throws IOException, ParseException,
                FaultTolerantRESTRequest.RetryRequestedException
        {
            int statusCode = response.getStatusLine().getStatusCode();

            if (200 == statusCode || 201 == statusCode)
            {
                // OK
                result = new ApiResult(statusCode);
            }
            else if ((statusCode >= 400) && (statusCode < 500))
            {
                // Client side error, indicates that the
                // reservation backend do not want the resubmission
                ErrorResponse error = readErrorResponse(response);

                result = new ApiResult(statusCode, error);
            }
            else
            {
                // Unusual status code, request the retry
                throw new FaultTolerantRESTRequest.RetryRequestedException();
            }

        }

    }

    /**
     *  The base class for dealing with conference data JSON response handling
     */
    protected class ReadConferenceResponseParser
            extends AbstractRESTResponseParser
    {

        /**
         * The conference instance that relates to this parti
         */
        protected Conference conference;

        /**
         * Parse the room read response received from reservation backend.
         *
         * This will request the retry if the response status code differs from
         * 200, 201 or 4xx.
         *
         * @param response The Apache HTTP client response
         * @throws IOException
         * @throws ParseException
         * @throws FaultTolerantRESTRequest.RetryRequestedException
         */
        @Override
        protected void parse(HttpResponse response)
                throws
                IOException,
                ParseException,
                FaultTolerantRESTRequest.RetryRequestedException
        {

            int statusCode = response.getStatusLine().getStatusCode();

            logger.info("STATUS CODE: " + statusCode);

            if (200 == statusCode || 201 == statusCode)
            {
                // OK
                conference = readConferenceResponse(conference, response);

                result = new ApiResult(statusCode, conference);
            }
            else if ((statusCode >= 400) && (statusCode < 500))
            {
                // Client side error, indicates that the reservation
                // backend do not want the resubmission
                ErrorResponse error = readErrorResponse(response);

                result = new ApiResult(statusCode, error);

            }
            else
            {

                // Unusual status code, request the retry
                throw new FaultTolerantRESTRequest.RetryRequestedException();

            }

        }

    }

    /**
     *  The response parser to use for conflict resolving
     *  reservation system callback
     */
    protected class GetConferenceResponseParser
            extends ReadConferenceResponseParser
    {

        /**
         * Instantiate the <tt>GetConferenceResponseParser</tt>
         */
        public GetConferenceResponseParser()
        {
            this.conference = null;
        }

    }

    /**
     *  The response parser to use for conference creating
     *  reservation system callback
     */
    protected class CreateConferenceResponseParser
            extends ReadConferenceResponseParser
    {

        /**
         * Instantiate the <tt>GetConferenceResponseParser</tt>
         * with a given conference instance
         * @param conference the <tt>Conference</tt> instance
         *                   that is ought to perform communication callback
         */
        public CreateConferenceResponseParser(Conference conference)
        {
            this.conference = conference;
        }

    }

}
