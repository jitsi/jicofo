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

import org.jitsi.util.*;
import org.json.simple.parser.*;

import java.io.*;

/**
 * Abstract template class for implementing JSON paring with
 * {@link ContentHandler} interface.
 *
 * @author Pawel Domas
 */
public abstract class AbstractJsonHandler<T>
    implements ContentHandler
{
    /**
     * Variable stores currently parsed object key name.
     */
    protected String currentKey;

    /**
     * Variable stores currently edited instance. It can be set with {@link
     * #setForUpdate(Object)}.
     */
    protected T editedInstance;

    /**
     * Variable stores result object created during parsing.
     */
    protected T result;

    /**
     * If method is called before this handler is used for parsing then
     * passed instance will have it's fields edited during the process and no
     * new instance will be created.
     *
     * @param instanceToUpdate the instance which fields should be updated
     *                         during parsing.
     */
    public void setForUpdate(T instanceToUpdate)
    {
        this.editedInstance = instanceToUpdate;
    }

    /**
     * Returns parsed object.
     */
    public T getResult()
    {
        return result;
    }

    @Override
    public void startJSON()
        throws ParseException, IOException
    {
        if (this.editedInstance == null)
        {
            editedInstance = createNewObject();
        }
        this.result = null;
    }

    /**
     * Implement new instance creation fo the template object.
     */
    protected abstract T createNewObject();

    @Override
    public void endJSON()
        throws ParseException, IOException
    {
        this.result = editedInstance;
        this.editedInstance = null;
    }

    @Override
    public boolean startObject()
        throws ParseException, IOException
    {
        return true;
    }

    @Override
    public boolean endObject()
        throws ParseException, IOException
    {
        return true;
    }

    @Override
    public boolean startObjectEntry(String key)
        throws ParseException, IOException
    {
        this.currentKey = key;

        return true;
    }

    @Override
    public boolean endObjectEntry()
        throws ParseException, IOException
    {
        currentKey = null;

        return true;
    }

    @Override
    public boolean startArray()
        throws ParseException, IOException
    {
        return false;
    }

    @Override
    public boolean endArray()
        throws ParseException, IOException
    {
        return false;
    }

    /**
     * Utility method for checking if given <tt>primitive</tt> is a
     * <tt>String</tt>.
     *
     * @param primitive the object to check
     *
     * @throws ParseException if given <tt>primitive</tt> is not instance of
     *         <tt>String</tt>
     */
    protected void assertString(Object primitive)
        throws ParseException
    {
        if (!(primitive instanceof String))
        {
            throw new ParseException(
                    ParseException.ERROR_UNEXPECTED_TOKEN, primitive);
        }
    }

    /**
     * Utility method for checking if given <tt>primitive</tt> is a
     * <tt>Number</tt>.
     *
     * @param primitive the object to check
     *
     * @throws ParseException if given <tt>primitive</tt> is not instance of
     *         <tt>Number</tt>
     */
    protected void assertNumber(Object primitive)
        throws ParseException
    {
        if (!(primitive instanceof Number))
        {
            throw new ParseException(
                    ParseException.ERROR_UNEXPECTED_TOKEN, primitive);
        }
    }

    /**
     * Methods is used to verify if given <tt>oldValue</tt> has not been
     * modified during parsing. It can be used to enforce read-only policy on
     * some fields. Exception will be thrown only if <tt>oldValue</tt> is not
     * <tt>null</tt> nor empty which means that it has been set on edited
     * instance.
     *
     * @param oldValue old value of the field to be checked. If <tt>null</tt>
     *                 no exception will be thrown which means that value has
     *                 not been assigned yet.
     * @param newValue the new value to be verified if it matches the
     *                 previous one(if old value is not null nor empty).
     * @param key the name of read-only key which will be included in
     *            exception message.
     *
     * @return <tt>true</tt> if read only policy has not been violated.
     *
     * @throws ParseException if <tt>oldValue</tt> is not null nor empty and
     *         <tt>newValue</tt> is not equal to the same as the old one.
     */
    protected boolean checkImmutableString(String oldValue, String newValue,
                                           String key)
        throws ParseException
    {
        if(!StringUtils.isNullOrEmpty(oldValue) && !oldValue.equals(newValue))
        {
            throw new ParseException(
                ParseException.ERROR_UNEXPECTED_EXCEPTION,
                "Attempt to modify immutable " + key + " property: "
                + oldValue + " -> " + newValue);
        }
        else
        {
            return true;
        }
    }
}
