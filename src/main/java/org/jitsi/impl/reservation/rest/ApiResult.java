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


/**
 * Structure used for holding processing results. It contains HTTP status
 * code, <tt>Conference</tt> instance which represents the data retrieved
 * from the API or <tt>ErrorResponse</tt> which contains error details.
 *
 * @author Pawel Domas
 */
public class ApiResult
{
    /**
     * Status code returned by the API
     */
    private int statusCode;

    /**
     * <tt>Conference</tt> instance related to particular API request
     */
    private Conference conference;

    private ErrorResponse error;

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
        this.setError(error);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString()
    {
        return "ApiError[" + getStatusCode() + "](" + getError() + ")";
    }

    /**
     * HTTP status code returned by the API.
     */
    public int getStatusCode() {
        return statusCode;
    }

    /**
     * <tt>Conference</tt> instance filled with data retrieved from the API.
     */
    public Conference getConference() {
        return conference;
    }

    public void setError(ErrorResponse error) {
        this.error = error;
    }

    /**
     * <tt>ErrorResponse</tt> instance got from the API
     */
    public ErrorResponse getError() {
        return error;
    }
}