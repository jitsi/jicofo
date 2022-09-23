/*
 * Copyright @ 2018 - present 8x8, Inc.
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
