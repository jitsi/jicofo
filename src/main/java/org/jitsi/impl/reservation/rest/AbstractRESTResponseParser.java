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
import org.apache.http.*;
import org.json.simple.parser.ParseException;

/**
 *
 * This interface implementers are intended to be used to provide response
 * parsers and validators for <tt>FaultTolerantRESTRequest</tt>
 *
 *  @author Maksym Kulish
 */
public abstract class AbstractRESTResponseParser
{

    /**
     * The API response parsing result
     */
    protected ApiResult result;

    /**
     * Parse the result and return the API response
     *
     * @param response The Apache HTTP client response
     */
    abstract protected void parse(HttpResponse response) throws IOException,
            ParseException, FaultTolerantRESTRequest.RetryRequestedException;


    /**
     * Get the resulting API response
     *
     * @param response The Apache <tt>HttpClient</tt> response
     * @return resulting API response wrapper
     * @throws IOException When an input/output error occurs during the API
     *                     communication cycle
     * @throws ParseException When a JSON parse error occurs
     * @throws FaultTolerantRESTRequest.RetryRequestedException When the retry
     *                     is requested by response parser implementation
     */
    public ApiResult getResult(HttpResponse response)
            throws IOException,
            ParseException,
            FaultTolerantRESTRequest.RetryRequestedException
    {

        try
        {
            parse(response);
            return this.result;
        }
        finally
        {
            if (response != null && response.getEntity() != null)
            {
                response.getEntity().consumeContent();
            }
        }

    }

}
