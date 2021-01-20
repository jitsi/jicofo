/*
 * Jitsi, the OpenSource Java VoIP and Instant Messaging client.
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
package org.jitsi.impl.protocol.xmpp;

/**
 * <tt>OperationFailedException</tt> indicates an exception that occurred in the
 * API.
 * <p>
 * <tt>OperationFailedException</tt> contains an error code that gives more
 * information on the exception. The application can obtain the error code using
 * {@link OperationFailedException#getErrorCode()}. The error code values are
 * defined in the <tt>OperationFailedException</tt> fields.
 * </p>
 *
 * @author Emil Ivov
 */
public class OperationFailedException
    extends Exception
{
    /**
     * Serial version UID.
     */
    private static final long serialVersionUID = 0L;

    /**
     * Set when no other error code can describe the exception that occurred.
     */
    public static final int GENERAL_ERROR = 1;

    /**
     * Set to indicate that a provider needs to be registered or signed on
     * a public service before calling the method that threw the exception.
     */
    public static final int PROVIDER_NOT_REGISTERED = 3;

    /**
     * Indicates that the exception was thrown because a method has been
     * passed an illegal or inappropriate argument.
     */
    public static final int ILLEGAL_ARGUMENT = 11;

    /**
     * The error code of the exception
     */
    private final int errorCode;

    /**
     * Creates an exception with the specified error message and error code.
     * @param message A message containing details on the error that caused the
     * exception
     * @param errorCode the error code of the exception (one of the error code
     * fields of this class)
     */
    public OperationFailedException(String message, int errorCode)
    {
        super(message);
        this.errorCode = errorCode;
    }

    /**
     * Creates an exception with the specified message, errorCode and cause.
     * @param message A message containing details on the error that caused the
     * exception
     * @param errorCode the error code of the exception (one of the error code
     * fields of this class)
     * @param cause the error that caused this exception
     */
    public OperationFailedException(String message,
                                    int errorCode,
                                    Throwable cause)
    {
        super(message, cause);

        this.errorCode = errorCode;
    }

    /**
     * Obtain the error code value.
     *
     * @return the error code for the exception.
     */
    public int getErrorCode()
    {
        return errorCode;
    }
}
