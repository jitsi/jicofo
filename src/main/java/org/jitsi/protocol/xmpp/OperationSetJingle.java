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

import net.java.sip.communicator.impl.protocol.jabber.extensions.jingle.*;
import net.java.sip.communicator.service.protocol.*;

import org.jitsi.protocol.xmpp.util.*;

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
     * Start new session by sending 'session-initiate' IQ to given XMPP address.
     *
     * @param useBundle <tt>true</tt> if contents description in the IQ sent
     *                  should contain additional signaling required for RTP
     *                  bundle usage in Jitsi Meet.
     * @param address the XMPP address that will be remote destination of new
     *                Jingle session.
     * @param contents media contents description of our offer.
     * @param requestHandler <tt>JingleRequestHandler</tt> that will be bound
     *                       to new Jingle session instance.
     * @param startMuted if the first element is <tt>true</tt> the participant
     * will start audio muted. if the second element is <tt>true</tt> the
     * participant will start video muted.
     *
     * @return <tt>true</tt> if have have received RESULT response to
     *         session-initiate IQ.
     */
    boolean initiateSession(
            boolean useBundle,
            String address,
            List<ContentPacketExtension> contents,
            JingleRequestHandler requestHandler,
            boolean[] startMuted);

    /**
     * Sends 'transport-replace' IQ to the client.
     *
     * @param useBundle <tt>true</tt> if bundled transport is being used or
     * <tt>false</tt> otherwise
     * @param session the <tt>JingleSession</tt> used to send the notification.
     * @param contents the list of Jingle contents which describes the actual
     * offer
     * @param startMuted an array where the first value stands for "start with
     * audio muted" and the seconds one for "start video muted"
     *
     * @return <tt>true</tt> if have have received RESULT response to the IQ.
     */
    boolean replaceTransport(boolean                         useBundle,
                             JingleSession                   session,
                             List<ContentPacketExtension>    contents,
                             boolean[]                       startMuted);

    /**
     * Sends 'source-add' proprietary notification.
     *
     * @param ssrcMap the media SSRCs map which will be included in
     *                the notification.
     * @param ssrcGroupMap the map of media SSRC groups that will be included in
     *                     the notification.
     * @param session the <tt>JingleSession</tt> used to send the notification.
     */
    void sendAddSourceIQ(MediaSSRCMap ssrcMap,
                         MediaSSRCGroupMap ssrcGroupMap,
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
    void sendRemoveSourceIQ(MediaSSRCMap ssrcMap,
                            MediaSSRCGroupMap ssrcGroupMap,
                            JingleSession session);

    /**
     * Terminates given session by sending 'session-terminate' IQ which will
     * optionally include the <tt>Reason</tt> supplied.
     *
     * @param session the <tt>JingleSession</tt> to be terminated.
     * @param reason optional <tt>Reason</tt> specifying the reason of session
     *               termination.
     * @param message optional text message providing more details about
     *                the reason for terminating the session.
     */
    void terminateSession(JingleSession session, Reason reason, String message);

    /**
     * Terminates all active Jingle Sessions associated with given
     * <tt>JingleRequestHandler</tt>.
     * @param requestHandler <tt>JingleRequestHandler</tt> instance for which
     *                       all active JingleSessions shall be terminated.
     */
    void terminateHandlersSessions(JingleRequestHandler requestHandler);
}
