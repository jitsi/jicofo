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
package org.jitsi.jicofo.auth;

import org.jitsi.impl.protocol.xmpp.extensions.*;
import org.jitsi.jicofo.reservation.*;
import org.jivesoftware.smack.packet.*;

/**
 * Utility class for creating XMPP error responses.
 *
 * @author Pawel Domas
 */
public class ErrorFactory
{
    /**
     * Creates 'not-authorized' XMPP error response to given <tt>query</tt>.
     *
     * @param query the IQ for which error response wil be created.
     * @param msg optional error description to be included in the response.
     *
     * @return XMPP 'not-authorized' error response to given <tt>query</tt>.
     */
    public static IQ createNotAuthorizedError(IQ query, String msg)
    {
        final XMPPError error
            = new XMPPError(XMPPError.Condition.not_authorized, msg);

        return IQ.createErrorResponse(query, error);
    }

    /**
     * Creates 'not-acceptable' XMPP error response with application specific
     * 'session-invalid' error extension.
     *
     * @param query the IQ for which error response will be created.
     *
     * @return XMPP 'not-acceptable' error response to given <tt>query</tt>
     *         with application specific 'session-invalid' extension.
     */
    public static IQ createSessionInvalidResponse(IQ query)
    {
        final XMPPError error
            = new XMPPError(
                    XMPPError.Condition.no_acceptable, "invalid session");

        // session-invalid application specific error
        error.addExtension(new SessionInvalidPacketExtension());

        return IQ.createErrorResponse(query, error);
    }

    /**
     * Creates 'not-acceptable' XMPP error response to given <tt>query</tt>.
     *
     * @param query the IQ for which error response will be created.
     *
     * @param errorMessage application specific error message included in
     *                     error response.
     *
     * @return 'not-acceptable' XMPP error response to given <tt>query</tt> with
     *          included <tt>errorMessage</tt>.
     */
    public static IQ createNotAcceptableError(IQ query, String errorMessage)
    {
        // not acceptable
        final XMPPError error
            = new XMPPError(XMPPError.Condition.no_acceptable, errorMessage);

        return IQ.createErrorResponse(query, error);
    }

    /**
     * Creates XMPP error response which will describe given
     * <tt>ReservationSystem.Result</tt>.
     *
     * @param query query IQ for which XMPP error response will be crated.
     * @param result reservation system result which contains all details about
     *               specific reservation error.
     *
     * @return XMPP error response which describes given
     *         <tt>ReservationSystem.Result</tt>.
     */
    public static IQ createReservationError(ConferenceIq query,
                                            ReservationSystem.Result result)
    {
        final XMPPError error
            = new XMPPError(
                    XMPPError.Condition.service_unavailable,
                    result.getErrorMessage());

        ReservationErrorPacketExt reservationErr
            = new ReservationErrorPacketExt();

        reservationErr.setErrorCode(result.getCode());

        error.addExtension(reservationErr);

        return IQ.createErrorResponse(query, error);
    }
}
