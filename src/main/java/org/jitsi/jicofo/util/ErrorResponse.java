package org.jitsi.jicofo.util;

import org.jivesoftware.smack.packet.*;

/**
 * Utility for Error IQ.
 */
public class ErrorResponse
{
    /**
     * A convenience method for creating error responses.
     *
     * @param request the {@link IQ.Type#get IQ.Type.get} or
     * {@link IQ.Type#set IQ.Type.set} IQ packet.
     * @param condition the {@link StanzaError.Condition} for the error.
     * @param text human readable error description.
     * @return ErrorIQ
     */
    static public ErrorIQ create(
            IQ request, StanzaError.Condition condition, String text)
    {
        return IQ.createErrorResponse(
                request,
                StanzaError.from(condition, text).build());
    }
}
