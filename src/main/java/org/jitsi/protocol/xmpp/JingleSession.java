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
package org.jitsi.protocol.xmpp;

/**
 * Class describes Jingle session.
 *
 * @author Pawel Domas
 */
public class JingleSession
{
    /**
     * Jingle session identifier.
     */
    private final String sid;

    /**
     * Remote peer XMPP address.
     */
    private final String address;

    /**
     * <tt>JingleRequestHandler</tt> that is processing requests for this
     * session.
     */
    private final JingleRequestHandler requestHandler;

    /**
     * Creates new instance of <tt>JingleSession</tt> for given parameters.
     *
     * @param sid Jingle session identifier of new instance.
     * @param address remote peer XMPP address.
     * @param requestHandler request handler that will be associated with
     *                       newly created instance.
     */
    public JingleSession(String sid, String address,
                         JingleRequestHandler requestHandler)
    {
        if (requestHandler == null)
        {
            throw new NullPointerException("requestHandler");
        }
        this.sid = sid;
        this.address = address;
        this.requestHandler = requestHandler;
    }

    /**
     * Returns Jingle session identifier.
     */
    public String getSessionID()
    {
        return sid;
    }

    /**
     * Returns remote peer's full XMPP address.
     */
    public String getAddress()
    {
        return address;
    }

    /**
     * Returns <tt>JingleRequestHandler</tt> that is responsible for handling
     * request for this Jingle session.
     */
    public JingleRequestHandler getRequestHandler()
    {
        return requestHandler;
    }
}
