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
package org.jitsi.jicofo.reservation;

import java.util.*;
import mock.MockHttpClient;
import org.apache.http.*;
import org.jitsi.impl.reservation.rest.*;
import org.junit.*;
import org.junit.rules.*;


import static org.junit.Assert.*;


/**
 * @author Maksym Kulish
 */
public class ApiHandlerTest extends HTTPTestUtil
{

    @Rule
    public final ExpectedException expectedException = ExpectedException.none();

    @Test
    public void testCreateConferenceSuccessWithoutRetries() throws Exception
    {

        String confJson =
                "{\n" +
                        "\"id\": 1234,\n" +
                        "\"duration\": 90001,\n" +
                        "\"name\": \"spam\",\n" +
                        "\"mail_owner\": \"elvis@presley.com\",\n" +
                        "\"start_time\": \"2015-02-23T09:03:34.000Z\"" +
                        "}";

        MockHttpClient httpClient = new MockHttpClient(
                getResponseEntity(200, "OK", confJson)
        );

        ApiHandler apiHandler = new ApiHandler(
                "http://my-wonderful-test-domain", httpClient
        );

        ApiResult conferenceCreationApiResult =
                apiHandler.createNewConference("elvis@presley.com", "spam");

        assertEquals(
                conferenceCreationApiResult.getConference().getName(), "spam");
        assertEquals(
                conferenceCreationApiResult.getConference().getOwner(),
                "elvis@presley.com"
        );
        assertEquals(
                conferenceCreationApiResult.getConference().getDuration(), 90001
        );

    }

    @Test
    public void testDeleteConferenceSuccessWithoutRetries() throws Exception
    {

        MockHttpClient httpClient = new MockHttpClient(
                getResponseEntity(200, "OK", "")
        );

        ApiHandler apiHandler = new ApiHandler(
                "http://my-wonderful-test-domain", httpClient
        );

        ApiResult conferenceDeletionApiResult =
                apiHandler.deleteConference(18000);

        assertEquals(200, conferenceDeletionApiResult.getStatusCode());

    }

    @Test
    public void testResolveConflictSuccessWithoutRetries() throws Exception
    {

        String confJson =
                "{\n" +
                        "\"id\": 1234,\n" +
                        "\"duration\": 90001,\n" +
                        "\"name\": \"spam\",\n" +
                        "\"mail_owner\": \"elvis@presley.com\",\n" +
                        "\"start_time\": \"2015-02-23T09:03:34.000Z\"" +
                        "}";

        MockHttpClient httpClient = new MockHttpClient(
                getResponseEntity(200, "OK", confJson)
        );

        ApiHandler apiHandler = new ApiHandler(
                "http://my-wonderful-test-domain", httpClient);

        ApiResult conferenceCreationApiResult = apiHandler.getConference(1234);

        assertEquals(
                conferenceCreationApiResult.getConference().getName(), "spam"
        );
        assertEquals(
                conferenceCreationApiResult.getConference().getOwner(),
                "elvis@presley.com"
        );
        assertEquals(
                conferenceCreationApiResult.getConference().getDuration(), 90001
        );

    }

    @Test
    public void testCreateConferenceSuccessWithRetries() throws Exception
    {

        HttpResponse failedResponse = getResponseEntity(
                500, "Internal Server Error", "I AM A NASTY TRACEBACK!");

        String confJson =
                "{\n" +
                        "\"id\": 1234,\n" +
                        "\"duration\": 90002,\n" +
                        "\"name\": \"spam\",\n" +
                        "\"mail_owner\": \"elvis@presley.com\",\n" +
                        "\"start_time\": \"2015-02-23T09:03:34.000Z\"" +
                        "}";

        HttpResponse successfulResponse = getResponseEntity(200, "OK", confJson);

        List<HttpResponse> responseList = new ArrayList<HttpResponse>();
        responseList.add(failedResponse);
        responseList.add(successfulResponse);

        MockHttpClient httpClient = new MockHttpClient(
                responseList
        );

        ApiHandler apiHandler = new ApiHandler(
                "http://my-wonderful-test-domain", httpClient);

        ApiResult conferenceCreationApiResult = apiHandler.createNewConference(
                "elvis@presley.com", "spam");

        assertEquals(
                conferenceCreationApiResult.getConference().getName(), "spam");
        assertEquals(
                conferenceCreationApiResult.getConference().getOwner(),
                "elvis@presley.com");
        assertEquals(
                conferenceCreationApiResult.getConference().getDuration(),
                90002);


    }

    @Test
    public void testResolveConflictSuccessWithRetries() throws Exception
    {

        HttpResponse failedResponse =
                getResponseEntity(
                        500, "Internal Server Error", "I AM A NASTY TRACEBACK!"
                );

        String confJson =
                "{\n" +
                        "\"id\": 1234,\n" +
                        "\"duration\": 90004,\n" +
                        "\"name\": \"spam\",\n" +
                        "\"mail_owner\": \"elvis@presley.com\",\n" +
                        "\"start_time\": \"2015-02-23T09:03:34.000Z\"" +
                        "}";

        HttpResponse successfulResponse = getResponseEntity(
                200, "OK", confJson);

        List<HttpResponse> responseList = new ArrayList<HttpResponse>();
        responseList.add(failedResponse);
        responseList.add(successfulResponse);

        MockHttpClient httpClient = new MockHttpClient(
                responseList
        );

        ApiHandler apiHandler = new ApiHandler(
                "http://my-wonderful-test-domain", httpClient);

        ApiResult resolveConflictApiResult = apiHandler.getConference(1234);

        assertEquals(
                resolveConflictApiResult.getConference().getName(), "spam");
        assertEquals(
                resolveConflictApiResult.getConference().getOwner(),
                "elvis@presley.com");
        assertEquals(
                resolveConflictApiResult.getConference().getDuration(), 90004);

    }

    @Test
    public void testResolveConflictFailure() throws Exception
    {


        MockHttpClient httpClient = new MockHttpClient(
                getResponseEntity(
                        500, "Internal Server Error", "I AM A NASTY TRACEBACK!")
        );

        ApiHandler apiHandler = new ApiHandler(
                "http://my-wonderful-test-domain", httpClient, 1, 1, 1);

        expectedException.expect(
                FaultTolerantRESTRequest.RetryExhaustedException.class);

        apiHandler.getConference(1234);

    }

    @Test
    public void testRoomDeleteFailure() throws Exception
    {


        MockHttpClient httpClient = new MockHttpClient(
                getResponseEntity(
                        500, "Internal Server Error", "I AM A NASTY TRACEBACK!"
                )
        );

        ApiHandler apiHandler =
                new ApiHandler(
                        "http://my-wonderful-test-domain", httpClient, 1, 1, 1);

        expectedException.expect(
                FaultTolerantRESTRequest.RetryExhaustedException.class);

        apiHandler.deleteConference(1234);

    }

    @Test
    public void testRoomCreateFailure() throws Exception
    {


        MockHttpClient httpClient = new MockHttpClient(
                getResponseEntity(
                        500, "Internal Server Error", "I AM A NASTY TRACEBACK!")
        );

        ApiHandler apiHandler = new ApiHandler(
                "http://my-wonderful-test-domain", httpClient, 1, 1, 1);

        expectedException.expect(
                FaultTolerantRESTRequest.RetryExhaustedException.class);

        apiHandler.createNewConference("elvis@presley.com", "spam");
    }


}
