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
package org.jitsi.jicofo.log;

import org.jitsi.util.*;

import java.io.*;
import java.util.zip.*;

/**
 * Static utility methods and fields related to logging.
 *
 * @author Boris Grozev
 */
public class LogUtil
{
    /**
     * The logger instance used by this class.
     */
    private final static Logger logger
            = Logger.getLogger(LogUtil.class);

    /**
     * The string which identifies the contents of a log message as
     * containing PeerConnection statistics.
     */
    public static final String LOG_ID_PC_STATS = "PeerConnectionStats";

    /**
     * Extracts the message to be logged from a <tt>LogPacketExtension</tt>.
     * Takes care of base64 decoding and (optionally) decompression.
     * @param log the <tt>LogPacketExtension</tt> to handle.
     * @return the decoded message contained in <tt>log</tt>.
     */
    public static String getContent(LogPacketExtension log)
    {
        String messageBase64 = log.getMessage();
        byte[] messageBytes
                = net.java.sip.communicator.util.Base64.decode(messageBase64);

        if (Boolean.parseBoolean(log.getTagValue("deflated")))
        {
            // nowrap=true, because we expect "raw" deflate
            Inflater inflater = new Inflater(true);
            ByteArrayOutputStream result = new ByteArrayOutputStream();

            inflater.setInput(messageBytes);
            byte[] buf = new byte[10000];

            do
            {
                try
                {
                    int len = inflater.inflate(buf);
                    result.write(buf, 0, len);
                }
                catch (DataFormatException dfe)
                {
                    if (logger.isInfoEnabled())
                        logger.info(
                                "Failed to inflate log request content:" + dfe);
                    return null;
                }
            } while (!inflater.finished());

            return result.toString();
        }
        else
        {
            return new String(messageBytes);
        }
    }
}
