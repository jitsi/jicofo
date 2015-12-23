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

import org.apache.http.*;
import org.apache.http.entity.*;
import org.apache.http.message.*;

import java.io.UnsupportedEncodingException;


/**
 * The HTTP test utility aimed to use as base class for test cases that
 * require HTTP communication mocking
 *
 *  @author Maksym Kulish
 */
public class HTTPTestUtil
{

    /**
     * Get the <tt>StatusLine</tt> instance to be used for response building
     *
     * @param statusCode The status code to be assigned to response
     * @param reasonPhrase The HTTP reason phrase
     * @return <tt>StatusLine</tt> object
     */
    protected StatusLine getStatusLine(
            final int statusCode, final String reasonPhrase)
    {

        return new StatusLine() {
            @Override
            public ProtocolVersion getProtocolVersion()
            {
                return new ProtocolVersion("HTTP", 1, 1);
            }

            @Override
            public int getStatusCode()
            {
                return statusCode;
            }

            @Override
            public String getReasonPhrase()
            {
                return reasonPhrase;
            }
        };

    }

    /**
     * Get the <tt>HttpResponse</tt> instance used as return value in http client
     * mocking
     *
     * @param statusCode The status code to be assigned to response
     * @param reasonPhrase The HTTP reason phrase
     * @param responseBody The string to be used as response body
     * @return <tt>HttpResponse</tt> instance
     * @throws UnsupportedEncodingException When the responseBody
     */
    protected HttpResponse getResponseEntity(
            final int statusCode, final String reasonPhrase,
            final String responseBody) throws UnsupportedEncodingException
    {

        BasicHttpResponse response = new BasicHttpResponse(
                getStatusLine(statusCode, reasonPhrase));
        StringEntity responseEntity = new StringEntity(responseBody);
        response.setEntity(responseEntity);
        return response;
    }

}
