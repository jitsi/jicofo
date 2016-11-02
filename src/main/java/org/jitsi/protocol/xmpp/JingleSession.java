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

import org.jitsi.assertions.*;

/**
 * Class describes Jingle session.
 *
 * @author Pawel Domas
 * @author Lyubomir Marinov
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
     * The indicator which determines whether a {@code session-accept} was
     * received from the remote peer in response to our {@code session-initiate}
     * which initialized this instance. Introduced to work around a case in
     * which we do not receive an acknowledgment from the remote peer in
     * response to our {@code session-initiate} but do receive a
     * {@code session-accept}.
     */
    private boolean _accepted = false;

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
        Assert.notNull(requestHandler, "requestHandler");

        this.sid = sid;
        this.address = address;
        this.requestHandler = requestHandler;
    }

    /**
     * Returns Jingle session identifier.
     *
     * @return Jingle session identifier
     */
    public String getSessionID()
    {
        return sid;
    }

    /**
     * Returns remote peer's full XMPP address.
     *
     * @return remote peer's full XMPP address
     */
    public String getAddress()
    {
        return address;
    }

    /**
     * Returns <tt>JingleRequestHandler</tt> that is responsible for handling
     * request for this Jingle session.
     *
     * @return <tt>JingleRequestHandler</tt> that is responsible for handling
     * request for this Jingle session
     */
    public JingleRequestHandler getRequestHandler()
    {
        return requestHandler;
    }

    /**
     * Determines whether a {@code session-accept} was received from the remote
     * peer in response to our {@code session-initiate} which initialized this
     * instance.
     *
     * @return {@code true} if a {@code session-accept} was received from the
     * remote peer in response to our {@code session-initiate} which initialized
     * this instance; otherwise, {@code false}
     */
    public boolean isAccepted()
    {
        return _accepted;
    }

    /**
     * Sets the indicator which determines whether a {@code session-accept} was
     * received from the remote peer in response to our {@code session-initiate}
     * which initialized this instance.
     *
     * @param accepted {@code true} if a {@code session-accept} was received
     * from the remote peer in response to our {@code session-initiate} which
     * initialized this instance; otherwise, {@code false}
     */
    public void setAccepted(boolean accepted)
    {
        _accepted = accepted;
    }
}
