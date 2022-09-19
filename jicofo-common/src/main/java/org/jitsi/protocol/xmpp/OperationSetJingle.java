/*
 * Jicofo, the Jitsi Conference Focus.
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
package org.jitsi.protocol.xmpp;

import org.jitsi.jicofo.conference.source.*;
import org.jitsi.xmpp.extensions.jingle.*;

import org.jivesoftware.smack.*;
import org.jivesoftware.smack.packet.*;
import org.jxmpp.jid.*;

import java.util.*;

/**
 * Operation set allows to establish and control Jingle sessions. Exposed
 * functionality is limited to the minimum required by Jitsi Meet conference.
 * {@link org.jitsi.protocol.xmpp.JingleRequestHandler}.
 *
 * @author Pawel Domas
 */
public interface OperationSetJingle
{
    /**
     * Initiates a Jingle session by sending the provided
     * {@code session-initiate} IQ. Blocks until a response is received or
     * until a timeout is reached.
     *
     * @param to the JID of the remote peer.
     * @param contents the list of {@link ContentPacketExtension} to include.
     * @param additionalExtensions additional extensions to add to the session-initiate stanza.
     * @param requestHandler <tt>JingleRequestHandler</tt> that will be bound
     * to new Jingle session instance.
     * @param sources the sources to include in the Jingle offer.
     * @param encodeSourcesAsJson whether to encode {@code sources} as JSON or standard Jingle.
     *
     * @return {@code true} the client didn't come back with en error response.
     */
    boolean initiateSession(
        Jid to,
        List<ContentPacketExtension> contents,
        List<ExtensionElement> additionalExtensions,
        JingleRequestHandler requestHandler,
        ConferenceSourceMap sources,
        boolean encodeSourcesAsJson)
        throws SmackException.NotConnectedException;

    Jid getOurJID();

    /**
     * Sends a 'transport-replace' IQ to the client. Blocks waiting for a response and returns {@code true} if a
     * response with type {@code result} is received before a certain timeout.
     *
     * @param session the <tt>JingleSession</tt> for which the IQ will be sent.
     * @param contents the list of {@link ContentPacketExtension} to include.
     * @param additionalExtensions additional extensions to add to the session-initiate stanza.
     * @param sources the sources to include in the Jingle offer.
     * @param encodeSourcesAsJson whether to encode {@code sources} as JSON or standard Jingle.
     *
     * @return {@code true} the client didn't come back with an error response.
     */
    boolean replaceTransport(
            JingleSession session,
            List<ContentPacketExtension> contents,
            List<ExtensionElement> additionalExtensions,
            ConferenceSourceMap sources,
            boolean encodeSourcesAsJson)
        throws SmackException.NotConnectedException;

    /**
     * Sends 'source-add' proprietary notification.
     *
     * @param sources the sources to be included in the source-add message.
     * @param session the <tt>JingleSession</tt> used to send the notification.
     * @param encodeSourcesAsJson whether to encode {@code sources} as JSON or standard Jingle.
     */
    void sendAddSourceIQ(ConferenceSourceMap sources, JingleSession session, boolean encodeSourcesAsJson);

    /**
     * Sends a 'source-remove' stanza to the remote Jingle peer.
     *
     * @param sourcesToRemove the sources to be included in the 'source-remove' stanza.
     * @param session the <tt>JingleSession</tt> used to send the notification.
     * @param encodeSourcesAsJson whether to encode {@code sources} as JSON or standard Jingle.
     */
    void sendRemoveSourceIQ(ConferenceSourceMap sourcesToRemove, JingleSession session, boolean encodeSourcesAsJson);

    /**
     * Terminates given Jingle session. This method is to be called either to send 'session-terminate' or to inform
     * this operation set that the session has been terminated as a result of 'session-terminate' received from
     * the other peer in which case {@code sendTerminate} should be set to {@code false}.
     *
     * @param session the <tt>JingleSession</tt> to terminate.
     * @param reason one of {@link Reason} enum that indicates why the session
     *               is being ended or <tt>null</tt> to omit.
     * @param sendTerminate when {@code true} it means that a 'session-terminate' is to be sent, otherwise it means
     * the session is being ended on the remote peer's request.
     */
    void terminateSession(JingleSession session, Reason reason, String message, boolean sendTerminate);

    /**
     * Terminates all active Jingle Sessions associated with given
     * <tt>JingleRequestHandler</tt>.
     * @param requestHandler <tt>JingleRequestHandler</tt> instance for which
     *                       all active JingleSessions shall be terminated.
     */
    void terminateHandlersSessions(JingleRequestHandler requestHandler);
}
