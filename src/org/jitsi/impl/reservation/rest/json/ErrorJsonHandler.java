/*
 * Jicofo, the Jitsi Conference Focus.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
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
