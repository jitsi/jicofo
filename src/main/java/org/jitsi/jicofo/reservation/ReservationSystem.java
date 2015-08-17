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

import org.jitsi.impl.protocol.xmpp.extensions.*;
import org.jitsi.jicofo.*;
import org.jitsi.jicofo.xmpp.*;

/**
 * FIXME: work in progress
 *
 * Interface for reservation system implementation. If room does not exist
 * {@link FocusComponent} will call {@link #createConference(String, String)}
 * method in order to verify that given user is allowed to create the room.
 * <tt>ReservationSystem</tt> itself is responsible for destroying conference
 * when it should expire by calling the method
 * {@link FocusManager#destroyConference(String, String)}.
 *
 * @author Pawel Domas
 */
public interface ReservationSystem
{
    /**
     * Room created successfully.
     */
    public static final int RESULT_OK = 1;

    /**
     * User is not allowed to create the room.
     */
    public static final int RESULT_NOT_ALLOWED = 2;

    /**
     * FIXME: not used, remove ?
     */
    public static final int RESULT_CONFLICT = 3;

    /**
     * Internal error has occurred during request processing. Room can not be
     * created.
     */
    public static final int RESULT_INTERNAL_ERROR = 100;

    /**
     * Tries to create new conference room with the reservation system.
     *
     * @param owner identity of conference owner user.
     * @param name FULL name of the conference room in the form of
     * room_name@muc.server.net.
     * @return <tt>Result</tt> with code {@link #RESULT_OK} if conference has
     * been created successfully or another code with error details if something
     * went wrong. Error details will be returned in XMPP error IQ containing
     * {@link ReservationErrorPacketExt}.
     */
    public Result createConference(String owner, String name);

    /**
     * Structure for returning result details.
     */
    class Result
    {
        /**
         * One of {@link ReservationSystem} RESULT_... constants.
         */
        private final int code;

        /**
         * Error message which should be displayed to the user.
         */
        private final String errorMessage;

        /**
         * Creates result without error message.
         * @param resultCode one of {@link ReservationSystem} RESULT_...
         *                   constants.
         */
        public Result(int resultCode)
        {
            this(resultCode, null);
        }

        /**
         * Creates result structure with error code and message.
         * @param errorCode one of {@link ReservationSystem} RESULT_...
         *                  constants.
         * @param errorMessage error message which will be displayed to the
         *                     user.
         */
        public Result(int errorCode, String errorMessage)
        {
            this.code = errorCode;
            this.errorMessage = errorMessage;
        }

        /**
         * Returns reservation system result code. One of
         * {@link ReservationSystem} RESULT_... constants.
         */
        public int getCode()
        {
            return code;
        }

        /**
         * Error message string(if any) which will be displayed to the user
         * in case of error result code.
         */
        public String getErrorMessage()
        {
            return errorMessage;
        }

        @Override
        public String toString()
        {
            return "Result[c=" + code + ", msg=" + errorMessage + "]@" +
                    hashCode();
        }
    }
}
