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
 * Reservation system error response.
 *
 * @author Pawel Domas
 */
public class ErrorResponse
{
    /**
     * Error name returned by the reservation system.
     */
    private String error;

    /**
     * Extra error message returned by the reservation system.
     */
    private String message;

    /**
     * In case of Conference POST conflict can occur. Reservation system will
     * return conflicting conference ID in response(which is stored in this
     * field).
     */
    private Number conflictId;

    /**
     * Sets error name returned by the reservation system.
     * @param error error <tt>String</tt> returned in reservation system
     *              response.
     */
    public void setError(String error)
    {
        this.error = error;
    }

    /**
     * Returns error name contained in reservation system response.
     */
    public String getError()
    {
        return error;
    }

    /**
     * Sets given <tt>String</tt> as error message of this response.
     * @param message error message string returned by the reservation system
     *                to be set on this instance.
     */
    public void setMessage(String message)
    {
        this.message = message;
    }

    /**
     * Returns error message contained in reservation system response.
     */
    public String getMessage()
    {
        return message;
    }

    /**
     * Sets conflicting conference ID returned in 409 response to Conference
     * POST request.
     *
     * @param conflictId conflicting conference identifier to set on this
     *                   instance.
     */
    public void setConflictId(Number conflictId)
    {
        this.conflictId = conflictId;
    }

    /**
     * Returns the ID of conflicting conference supplied in reservation system
     * response 409 to Conference POST request.
     */
    public Number getConflictId()
    {
        return conflictId;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String toString()
    {
        return "ErrorResponse[e: " + error + ", m: " + message
                + ", conflict: " + conflictId + "]@" + hashCode();
    }
}
