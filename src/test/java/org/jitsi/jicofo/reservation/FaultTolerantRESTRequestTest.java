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

import org.apache.http.message.*;
import org.junit.*;
import org.junit.rules.*;
import mock.*;
import mock.reservation.*;
import org.jitsi.impl.reservation.rest.*;

import java.io.IOException;

import static org.junit.Assert.*;


/**
 * @author Maksym Kulish
 */
public class FaultTolerantRESTRequestTest extends HTTPTestUtil
{

    /**
     * The JUnit expected exception waiter
     */
    @Rule
    public final ExpectedException expectedException = ExpectedException.none();

    @Test
    public void testSuccessfulResponseHandling() throws Exception
    {


        MockHttpClient httpClient = new MockHttpClient(
                new BasicHttpResponse(getStatusLine(200, "OK")));

        ApiResult result = new ApiResult(200);

        MockRESTResponseParser restResponseParser =
                new MockRESTResponseParser(result, 0);

        FaultTolerantRESTRequest faultTolerantRESTRequest =
                new FaultTolerantRESTRequest(null,
                restResponseParser, 0, httpClient);

        ApiResult resultOfSubmission = faultTolerantRESTRequest.submit();

        assertEquals(result, resultOfSubmission);

        assertEquals(0, restResponseParser.getRetryNumber());

    }

    @Test
    public void testLessNumberOfRetriesThanMax() throws Exception
    {

        MockHttpClient httpClient = new MockHttpClient(
                new BasicHttpResponse(getStatusLine(200, "OK")));

        ApiResult result = new ApiResult(200);

        MockRESTResponseParser restResponseParser =
                new MockRESTResponseParser(result, 2);

        restResponseParser.setIoException(new IOException());

        FaultTolerantRESTRequest faultTolerantRESTRequest =
                new FaultTolerantRESTRequest(null,
                restResponseParser, 3, httpClient);

        ApiResult resultOfSubmission = faultTolerantRESTRequest.submit();

        assertEquals(result, resultOfSubmission);

        assertEquals(2, restResponseParser.getRetryNumber());

        assertEquals(2, faultTolerantRESTRequest.getRetryNumber());

    }

    @Test
    public void testGreaterNumberOfRetriesThanMax() throws Exception
    {

        MockHttpClient httpClient = new MockHttpClient(
                new BasicHttpResponse(getStatusLine(200, "OK")));

        ApiResult result = new ApiResult(200);

        MockRESTResponseParser restResponseParser =
                new MockRESTResponseParser(result, 3);

        restResponseParser.setIoException(new IOException());

        FaultTolerantRESTRequest faultTolerantRESTRequest =
                new FaultTolerantRESTRequest(null,
                restResponseParser, 3, httpClient);

        this.expectedException.expect(
                FaultTolerantRESTRequest.RetryExhaustedException.class);

        ApiResult resultOfSubmission = faultTolerantRESTRequest.submit();

        assertEquals(null, resultOfSubmission);

        assertEquals(3, restResponseParser.getRetryNumber());

        assertEquals(3, faultTolerantRESTRequest.getRetryNumber());

    }

}
