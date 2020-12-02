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
package org.jitsi.impl.reservation.rest.json;

import org.jitsi.impl.reservation.rest.*;
import org.json.simple.parser.*;

import java.io.*;

/**
 * {@link ContentHandler} implementation for parsing JSON of error responses.
 *
 * @author Pawel Domas
 */
public class ErrorJsonHandler
    extends AbstractJsonHandler<ErrorResponse>
{
    /**
     * {@inheritDoc}
     */
    @Override
    protected ErrorResponse createNewObject()
    {
        return new ErrorResponse();
    }

    @Override
    public boolean primitive(Object primitive)
            throws ParseException, IOException
    {
        if ("error".equals(currentKey))
        {
            assertString(primitive);

            editedInstance.setError((String)primitive);
        }
        else if ("message".equals(currentKey))
        {
            assertString(primitive);

            editedInstance.setMessage((String)primitive);
        }
        else if ("conflict_id".equals(currentKey))
        {
            assertNumber(primitive);

            editedInstance.setConflictId((Number)primitive);
        }
        return true;
    }
}
