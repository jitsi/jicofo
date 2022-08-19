/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Copyright @ 2015-Present 8x8, Inc.
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

import org.jitsi.xmpp.extensions.jitsimeet.*;
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
        final StanzaError error
            = StanzaError.from(StanzaError.Condition.not_authorized, msg).build();

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
        final StanzaError error
            = StanzaError.from(
                    StanzaError.Condition.not_acceptable,
                    "invalid session")
                // session-invalid application specific error
                .addExtension(new SessionInvalidPacketExtension())
                .build();

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
        final StanzaError error
            = StanzaError.from(StanzaError.Condition.not_acceptable, errorMessage)
                .build();

        return IQ.createErrorResponse(query, error);
    }
}
