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

import org.jitsi.xmpp.extensions.jingle.*;
import net.java.sip.communicator.service.protocol.*;

import org.jitsi.protocol.xmpp.util.*;
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
    extends OperationSet
{
    /**
     * Initiates a Jingle session by sending the provided
     * {@code session-initiate} IQ. Blocks until a response is received or
     * until a timeout is reached.
     *
     * @param jingleIQ the IQ to send.
     * @param requestHandler <tt>JingleRequestHandler</tt> that will be bound
     * to new Jingle session instance.
     *
     * @return {@code true} the client didn't come back with en error response.
     *
     * @throws OperationFailedException with
     * {@link OperationFailedException#PROVIDER_NOT_REGISTERED} if the operation
     * fails, because the XMPP connection is broken.
     */
    boolean initiateSession(
        JingleIQ jingleIQ,
        JingleRequestHandler requestHandler)
        throws OperationFailedException;

    /**
     * Creates a {@code session-initiate} IQ for a specific address and adds
     * a list of {@link ContentPacketExtension} to it.
     *
     * @param address the destination JID.
     * @param contents the list of contents to add.
     *
     * @return the IQ which was created.
     */
    JingleIQ createSessionInitiate(
        Jid address,
        List<ContentPacketExtension> contents);

    /**
     * Sends a 'transport-replace' IQ to the client. Blocks waiting for a
     * response and returns {@code true} if a response with type {@code result}
     * is received before a certain timeout.
     *
     * @param jingleIQ the IQ which to be sent.
     * @param session the <tt>JingleSession</tt> for which the IQ will be sent.
     *
     * @return {@code true} the client didn't come back with an error response.
     *
     * @throws OperationFailedException with
     * {@link OperationFailedException#PROVIDER_NOT_REGISTERED} if the operation
     * fails, because the XMPP connection is broken.
     */
    boolean replaceTransport(JingleIQ jingleIQ, JingleSession session)
        throws OperationFailedException;

    /**
     * Creates a {@code transport-replace} packet for a particular
     * {@link JingleSession}.
     *
     * @param session the {@link JingleSession}.
     * @param contents the list of {@code content}s to include.
     * @return the IQ which was created.
     */
    JingleIQ createTransportReplace(
        JingleSession session,
        List<ContentPacketExtension> contents);

    /**
     * Sends 'source-add' proprietary notification.
     *
     * @param ssrcMap the media SSRCs map which will be included in
     *                the notification.
     * @param ssrcGroupMap the map of media SSRC groups that will be included in
     *                     the notification.
     * @param session the <tt>JingleSession</tt> used to send the notification.
     */
    void sendAddSourceIQ(MediaSourceMap ssrcMap,
                         MediaSourceGroupMap ssrcGroupMap,
                         JingleSession session);

    /**
     * Sends 'source-remove' notification to the peer of given
     * <tt>JingleSession</tt>.
     *
     * @param ssrcMap the map of media SSRCs that will be included in
     *                the notification.
     * @param ssrcGroupMap the map of media SSRC groups that will be included in
     *                     the notification.
     * @param session the <tt>JingleSession</tt> used to send the notification.
     */
    void sendRemoveSourceIQ(MediaSourceMap ssrcMap,
                            MediaSourceGroupMap ssrcGroupMap,
                            JingleSession session);

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
