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
package org.jitsi.impl.protocol.xmpp.log;

import java.io.*;
import java.text.*;
import java.time.*;
import java.util.*;
import java.util.logging.*;

/**
 * Logs XMPP traffic to a file if configured in the logging.properties file.
 */
public class XmppPacketsFileHandler extends FileHandler
{
    /**
     * A constructor.
     */
    public XmppPacketsFileHandler()
        throws IOException, SecurityException
    {
        setFilter(new IncludeXmppPackets());
        setFormatter(new Formatter());
        setLevel(Level.ALL);
    }

    /**
     * The formatter used by {@link XmppPacketsFileHandler}.
     */
    static class Formatter extends java.util.logging.Formatter
    {
        final SimpleDateFormat stampFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");

        @Override
        public String format(LogRecord record)
        {
            Date when = Date.from(Instant.ofEpochMilli(record.getMillis()));
            String msg = record.getMessage();

            return String.format("%s: %s%n", stampFormat.format(when), msg);
        }
    }
}
