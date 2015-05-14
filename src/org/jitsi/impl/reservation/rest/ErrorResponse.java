/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
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
