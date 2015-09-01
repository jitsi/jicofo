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
import java.util.*;
import net.java.sip.communicator.util.*;
import org.apache.http.*;
import org.apache.http.client.*;
import org.apache.http.client.methods.*;
import org.apache.http.impl.client.*;
import org.json.simple.parser.ParseException;


/**
 *
 * This decorator class brings fault tolerance to HTTP REST requests
 *
 *  @author Maksym Kulish
 */
public class FaultTolerantRESTRequest
{

    /**
     * Jitsi common logging utility facade object
     */
    protected final static Logger logger
            = Logger.getLogger(FaultTolerantRESTRequest.class);

    /**
     * System random generator used to generate randomization factor
     */
    protected final Random randomGenerator = new Random();

    /**
     * HTTP client used for sending requests.
     */
    protected final HttpClient client;

    /**
     * The HTTP request object to be submitted
     */
    protected HttpRequestBase request;

    /**
     * The factor constant to use in backoff algorithm
     */
    protected final double BACKOFF_FACTOR = 1.6180339887498948;

    /**
     * The jitter constant to use in backoff algorithm
     */
    protected final double BACKOFF_JITTER = 0.11962656472;

    /**
     * The maximum delay
     */
    protected final double MAX_DELAY = 30.0;

    /**
     * The response parser to be used to parse and validate the response
     */
    protected AbstractRESTResponseParser responseParser;

    /**
     * The number of attempts that have already been performed to
     * fetch the result
     */
    protected int retries = 0;

    /**
     * Current delay value, in seconds
     */
    protected double delay = 1.0;

    /**
     * The maximum retries value, the client will throw
     * <tt>RetryExhaustedException</tt>
     * when the number of consequential retries will reach it
     */
    protected int maxRetries;

    /**
     * Get current delay value in milliseconds
     *
     * @return The current delay value in milliseconds
     */
    protected int getDelayMillis()
    {
        return (int) Math.round(delay * 1000);
    }

    /**
     *  Compute the new delay value and wait until the next retransmission
     *  is ought to be performed
     */
    protected void waitUntilRetransmission()
    {

        delay = Math.min(delay * BACKOFF_FACTOR, MAX_DELAY);
        delay = delay +
                (randomGenerator.nextGaussian() - 0.5) * BACKOFF_JITTER * delay;
        try
        {
            Thread.sleep(getDelayMillis());
        }
        catch (InterruptedException e)
        {
            logger.error("Error while waiting for new retransmission", e);
            retries = maxRetries;
        }

    }

    /**
     * Handle the request exception
     *
     * @param exc The exception happened during API communication
     * @throws RetryExhaustedException When the maximum number of retries is
     *                                 reached
     */
    protected void handleApiException(Exception exc)
            throws RetryExhaustedException
    {
        logger.warn(
                "Error while submitting request to reservation backend", exc);
        retries++;

        if (retries >= maxRetries)
        {
            logger.error("Max retries exhaused for HTTP call");
            throw new RetryExhaustedException();
        }
        else
        {
            logger.info("Going to retry HTTP call");
        }

        waitUntilRetransmission();

    }

    /**
     * Create new instance of fault tolerant HTTP client
     *
     * @param request the Apache <tt>HttpUriRequest</tt> request instance
     * @param responseParser the response parser to be used for parsing
     *                       and validating the response
     * @param maxRetries Maximum retries allowed
     */
    public FaultTolerantRESTRequest(HttpRequestBase request,
                                    AbstractRESTResponseParser responseParser,
                                    int maxRetries)
    {
        this.request = request;
        this.responseParser = responseParser;
        this.maxRetries = maxRetries;
        this.client = new DefaultHttpClient();
    }

    /**
     * The constructor that allows to use custom HTTP client implementers
     *
     * @param request the Apache <tt>HttpUriRequest</tt> request instance
     * @param responseParser The response parser instance
     * @param maxRetries Maximum retries allowed
     * @param httpClient the Apache <tt>HttpClient</tt> request instance
     */
    public FaultTolerantRESTRequest(HttpRequestBase request,
                                    AbstractRESTResponseParser responseParser,
                                    int maxRetries, HttpClient httpClient)
    {
        this.request = request;
        this.responseParser = responseParser;
        this.maxRetries = maxRetries;
        this.client = httpClient;
    }

    /**
     * Perform the fault tolerant API communication cycle.
     *
     * @return API result wrapper
     * @throws RetryExhaustedException
     */
    public ApiResult submit() throws RetryExhaustedException
    {
        do
        {
            HttpResponse response = null;
            try
            {
                response = client.execute(request);
                return responseParser.getResult(response);

            } catch (IOException exc)
            {
                handleApiException(exc);
            } catch (ParseException exc)
            {
                handleApiException(exc);
            } catch (RetryRequestedException exc)
            {
                handleApiException(exc);
            }
        } while (true);

    }

    /**
     * Get the current retries number
     *
     * @return The number of currently performed consequential retries
     */
    public int getRetryNumber()
    {
        return retries;
    }

    /**
     * The exception to be thrown when the retry is requested
     */
    public static class RetryRequestedException extends Exception {};

    /**
     * The exception to be thrown when the maximum number of retries is reached
     */
    public static class RetryExhaustedException extends Exception {};


}
